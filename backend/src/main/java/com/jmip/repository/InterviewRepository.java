package com.jmip.repository;

import com.jmip.service.interview.InterviewQuestionGenerator.Question;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** V8.7: interview sessions and their questions. Every session read is scoped to its owner. */
@Repository
public class InterviewRepository {

    public record SessionRow(UUID id, Long jobId, UUID resumeId, String jobTitle, String companyName, String status,
                             OffsetDateTime createdAt, OffsetDateTime completedAt, String summary, BigDecimal averageScore,
                             int answered, int evaluated, int total, String interviewType, String difficulty,
                             int skipped) {
    }

    public record QuestionRow(int position, String category, String question, String focus, String answer,
                              OffsetDateTime answeredAt, String feedbackStatus, int evaluationAttempts, Integer relevance,
                              Integer completeness, Integer clarity, Integer technicalCorrectness, String strengths,
                              String improvements, OffsetDateTime evaluatedAt, Integer communication,
                              String suggestedApproach, OffsetDateTime skippedAt) {
    }

    private static final String SESSION_SELECT = """
            SELECT s.id, s.job_id, s.resume_id, s.job_title, s.company_name, s.status, s.created_at, s.completed_at,
                   s.summary, s.average_score,
                   count(q.id) FILTER (WHERE q.answer IS NOT NULL) AS answered,
                   count(q.id) FILTER (WHERE q.feedback_status = 'EVALUATED') AS evaluated,
                   count(q.id) AS total, s.interview_type, s.difficulty,
                   count(q.id) FILTER (WHERE q.feedback_status = 'SKIPPED') AS skipped
              FROM interview_sessions s LEFT JOIN interview_questions q ON q.session_id = s.id
            """;

    private final JdbcTemplate jdbcTemplate;

    public InterviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID id, UUID userId, Long jobId, UUID resumeId, String jobTitle, String companyName,
                       String interviewType, String difficulty, OffsetDateTime at, List<Question> questions) {
        jdbcTemplate.update("""
                INSERT INTO interview_sessions (id, user_id, job_id, resume_id, job_title, company_name, interview_type,
                                                difficulty, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, jobId, resumeId, jobTitle, companyName, interviewType, difficulty,
                Timestamp.from(at.toInstant()));
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            jdbcTemplate.update("INSERT INTO interview_questions (session_id, position, category, question, focus) "
                    + "VALUES (?, ?, ?, ?, ?)", id, i + 1, question.category(), question.text(), question.focus());
        }
    }

    public long countByUser(UUID userId) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM interview_sessions WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public Optional<SessionRow> find(UUID id, UUID userId) {
        return jdbcTemplate.query(SESSION_SELECT + " WHERE s.id = ? AND s.user_id = ? GROUP BY s.id", this::session, id, userId)
                .stream().findFirst();
    }

    public List<SessionRow> list(UUID userId) {
        return jdbcTemplate.query(SESSION_SELECT + " WHERE s.user_id = ? GROUP BY s.id ORDER BY s.created_at DESC",
                this::session, userId);
    }

    private static final String QUESTION_COLUMNS = """
            position, category, question, focus, answer, answered_at, feedback_status, evaluation_attempts,
            relevance, completeness, clarity, technical_correctness, strengths, improvements, evaluated_at,
            communication, suggested_approach, skipped_at
            """;

    public List<QuestionRow> questions(UUID sessionId) {
        return jdbcTemplate.query("SELECT " + QUESTION_COLUMNS + " FROM interview_questions WHERE session_id = ? ORDER BY position",
                (rs, row) -> question(rs, 0), sessionId);
    }

    /** V9.9: the questions of several sessions in one statement, keyed by session, in position order. */
    public Map<UUID, List<QuestionRow>> questions(Collection<UUID> sessionIds) {
        Map<UUID, List<QuestionRow>> bySession = new LinkedHashMap<>();
        if (sessionIds.isEmpty()) {
            return bySession;
        }
        jdbcTemplate.query("SELECT session_id, " + QUESTION_COLUMNS + " FROM interview_questions WHERE session_id = ANY (?) "
                        + "ORDER BY session_id, position",
                (java.sql.ResultSet rs) -> {
                    bySession.computeIfAbsent(rs.getObject(1, UUID.class), id -> new java.util.ArrayList<>()).add(question(rs, 1));
                },
                (Object) sessionIds.toArray(new UUID[0]));
        return bySession;
    }

    private static QuestionRow question(java.sql.ResultSet rs, int offset) throws java.sql.SQLException {
        return new QuestionRow(rs.getInt(offset + 1), rs.getString(offset + 2), rs.getString(offset + 3),
                rs.getString(offset + 4), rs.getString(offset + 5), rs.getObject(offset + 6, OffsetDateTime.class),
                rs.getString(offset + 7), rs.getInt(offset + 8), rs.getObject(offset + 9, Integer.class),
                rs.getObject(offset + 10, Integer.class), rs.getObject(offset + 11, Integer.class),
                rs.getObject(offset + 12, Integer.class), rs.getString(offset + 13), rs.getString(offset + 14),
                rs.getObject(offset + 15, OffsetDateTime.class), rs.getObject(offset + 16, Integer.class),
                rs.getString(offset + 17), rs.getObject(offset + 18, OffsetDateTime.class));
    }

    /** A new answer replaces the old one and its feedback. */
    public void saveAnswer(UUID sessionId, int position, String answer, OffsetDateTime at) {
        jdbcTemplate.update("""
                UPDATE interview_questions
                   SET answer = ?, answered_at = ?, feedback_status = 'NOT_ANSWERED', relevance = NULL, completeness = NULL,
                       clarity = NULL, technical_correctness = NULL, strengths = NULL, improvements = NULL, evaluated_at = NULL,
                       communication = NULL, suggested_approach = NULL, skipped_at = NULL
                 WHERE session_id = ? AND position = ?
                """, answer, Timestamp.from(at.toInstant()), sessionId, position);
    }

    public void saveEvaluation(UUID sessionId, int position, int relevance, int completeness, int clarity,
                               Integer technical, Integer communication, String strengths, String improvements,
                               String suggestedApproach, OffsetDateTime at) {
        jdbcTemplate.update("""
                UPDATE interview_questions
                   SET feedback_status = 'EVALUATED', evaluation_attempts = evaluation_attempts + 1, relevance = ?,
                       completeness = ?, clarity = ?, technical_correctness = ?, communication = ?, strengths = ?,
                       improvements = ?, suggested_approach = ?, evaluated_at = ?
                 WHERE session_id = ? AND position = ?
                """, relevance, completeness, clarity, technical, communication, strengths, improvements, suggestedApproach,
                Timestamp.from(at.toInstant()),
                sessionId, position);
    }

    public void markUnavailable(UUID sessionId, int position) {
        jdbcTemplate.update("UPDATE interview_questions SET feedback_status = 'UNAVAILABLE', "
                + "evaluation_attempts = evaluation_attempts + 1 WHERE session_id = ? AND position = ?", sessionId, position);
    }

    /** V9.6: an unanswered question is set aside; answering it later clears the skip. */
    public void skip(UUID sessionId, int position, OffsetDateTime at) {
        jdbcTemplate.update("UPDATE interview_questions SET feedback_status = 'SKIPPED', skipped_at = ? "
                + "WHERE session_id = ? AND position = ? AND answer IS NULL", Timestamp.from(at.toInstant()), sessionId, position);
    }

    public void complete(UUID sessionId, String summary, BigDecimal averageScore, OffsetDateTime at) {
        jdbcTemplate.update("UPDATE interview_sessions SET status = 'COMPLETED', summary = ?, average_score = ?, completed_at = ? "
                + "WHERE id = ?", summary, averageScore, Timestamp.from(at.toInstant()), sessionId);
    }

    private SessionRow session(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SessionRow(rs.getObject(1, UUID.class), rs.getObject(2, Long.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, OffsetDateTime.class), rs.getString(9), rs.getBigDecimal(10), rs.getInt(11), rs.getInt(12),
                rs.getInt(13), rs.getString(14), rs.getString(15), rs.getInt(16));
    }
}
