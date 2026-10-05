package com.jmip.service.interview;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.interview.InterviewDtos.Feedback;
import com.jmip.dto.interview.InterviewDtos.LearningSuggestion;
import com.jmip.dto.interview.InterviewDtos.Report;
import com.jmip.dto.interview.InterviewDtos.StartRequest;
import com.jmip.dto.learning.LearningDtos;
import com.jmip.dto.interview.InterviewDtos.QuestionResponse;
import com.jmip.dto.interview.InterviewDtos.SessionResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.entity.Job;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.repository.InterviewRepository;
import com.jmip.repository.InterviewRepository.QuestionRow;
import com.jmip.repository.InterviewRepository.SessionRow;
import com.jmip.repository.JobRepository;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.learning.LearningService;
import com.jmip.service.resume.ResumeKeywordAnalyzer;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * V8.7: interview preparation for the signed-in user. Questions come from
 * {@link InterviewQuestionGenerator} with the V8.3 match and V8.6 posting terms; answers are
 * scored by {@link InterviewEvaluator}. Sessions are the owner's only: another account's
 * session, like another account's resume, answers 404.
 *
 * <p>V9.6: a session has a type, a difficulty and a question count; questions can be skipped, and
 * a completed session carries a report with technical and behavioral scores, strong and weak areas
 * and matching items from the V9.5 learning plan. The plan is only read, never changed.
 *
 * <p>Nothing here holds a transaction open while the AI provider is called.
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    static final int MAX_SESSIONS_PER_ACCOUNT = 200;
    /** Evaluations per question, retries included; bounds provider use per answer. */
    static final int MAX_EVALUATIONS = 3;

    private final InterviewRepository repository;
    private final JobRepository jobRepository;
    private final ResumeService resumeService;
    private final ResumeMatchService matchService;
    private final ResumeKeywordAnalyzer keywords;
    private final InterviewQuestionGenerator generator;
    private final InterviewEvaluator evaluator;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final LearningService learning;
    private final org.springframework.transaction.support.TransactionTemplate readOnly;

    public InterviewService(InterviewRepository repository, JobRepository jobRepository, ResumeService resumeService,
                            ResumeMatchService matchService, ResumeKeywordAnalyzer keywords,
                            InterviewQuestionGenerator generator, InterviewEvaluator evaluator, CurrentUser currentUser,
                            Clock clock, org.springframework.transaction.PlatformTransactionManager transactionManager,
                            LearningService learning) {
        this.learning = learning;
        this.readOnly = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.resumeService = resumeService;
        this.matchService = matchService;
        this.keywords = keywords;
        this.generator = generator;
        this.evaluator = evaluator;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public SessionResponse start(StartRequest request) {
        UUID owner = currentUser.requireId();
        Long jobId = request.jobId();
        UUID resumeId = request.resumeId();
        InterviewQuestionGenerator.Setup setup = new InterviewQuestionGenerator.Setup(
                request.interviewType() == null ? "MIXED" : request.interviewType(),
                request.difficulty() == null ? "MEDIUM" : request.difficulty(), request.questionCount());
        if (repository.countByUser(owner) >= MAX_SESSIONS_PER_ACCOUNT) {
            throw new InvalidRequestException("You can keep at most " + MAX_SESSIONS_PER_ACCOUNT + " interview sessions");
        }
        Job job = jobRepository.findDetailById(jobId).orElseThrow(() -> ResourceNotFoundException.of("Job", jobId));
        // An explicit resume is owner-checked; otherwise the current processed one, if any.
        Optional<Resume> resume = resumeId != null
                ? Optional.of(resumeService.requireCompletedResume(resumeId))
                : resumeService.currentProcessedResumeId().map(resumeService::requireCompletedResume);

        List<String> jobSkills = job.getSkills().stream().map(Skill::getName).sorted().toList();
        List<String> matched = List.of();
        List<String> missing = jobSkills;
        List<String> resumeOnly = List.of();
        if (resume.isPresent()) {
            ResumeMatchResponse match = matchService.match(resume.get().getId(), jobId);
            matched = names(match.matchedSkills());
            missing = names(match.missingSkills());
            resumeOnly = names(match.resumeOnlySkills());
        }
        List<String> excluded = Stream.of(jobSkills, resumeOnly).flatMap(List::stream).toList();
        ResumeKeywordAnalyzer.Result terms = keywords.analyze(job.getTitle(), job.getDescription(),
                resume.map(Resume::getExtractedText).orElse(""), excluded);
        List<String> postingTerms = Stream.concat(terms.present().stream(), terms.missing().stream())
                .sorted(Comparator.comparingInt(ResumeKeywordAnalyzer.Term::jobMentions).reversed())
                .map(ResumeKeywordAnalyzer.Term::term)
                // The title's own words are already in every role question.
                .filter(term -> !job.getTitle().toLowerCase(java.util.Locale.ROOT).contains(term))
                .toList();

        List<InterviewQuestionGenerator.Question> questions = generator.generate(setup, job.getTitle(), job.getCompany().getName(),
                matched, missing, resumeOnly, postingTerms, experience(job), resume.isPresent());
        UUID id = UUID.randomUUID();
        repository.create(id, owner, job.getId(), resume.map(Resume::getId).orElse(null), job.getTitle(),
                job.getCompany().getName(), setup.type(), setup.difficulty(), now(), questions);
        log.info("Interview session {} started: {} {} with {} questions", id, setup.type(), setup.difficulty(), questions.size());
        return detail(id, owner);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> list() {
        return repository.list(currentUser.requireId()).stream().map(row -> response(row, null)).toList();
    }

    @Transactional(readOnly = true)
    public SessionResponse get(UUID id) {
        return detail(id, currentUser.requireId());
    }

    /** Saves the answer, then asks the AI provider to evaluate it. */
    public QuestionResponse answer(UUID id, int position, String answer) {
        UUID owner = currentUser.requireId();
        SessionRow session = requireOpen(id, owner);
        QuestionRow question = requireQuestion(session, position);
        requireAttemptsLeft(question);
        repository.saveAnswer(id, position, answer.strip(), now());
        return evaluate(session, position, owner);
    }

    /** Evaluates the saved answer again, for example after the provider was unavailable. */
    public QuestionResponse reevaluate(UUID id, int position) {
        UUID owner = currentUser.requireId();
        SessionRow session = requireOpen(id, owner);
        QuestionRow question = requireQuestion(session, position);
        if (question.answer() == null) {
            throw new InvalidRequestException("Answer the question before asking for feedback");
        }
        requireAttemptsLeft(question);
        return evaluate(session, position, owner);
    }

    /** V9.6: sets an unanswered question aside; it can still be answered before the interview ends. */
    public QuestionResponse skip(UUID id, int position) {
        UUID owner = currentUser.requireId();
        SessionRow session = requireOpen(id, owner);
        QuestionRow question = requireQuestion(session, position);
        if (question.answer() != null) {
            throw new InvalidRequestException("This question is already answered");
        }
        repository.skip(id, position, now());
        return question(requireQuestion(session, position), null);
    }

    /** Closes the session (also when ended early) with a summary computed from the stored feedback; no AI involved. */
    @Transactional
    public SessionResponse complete(UUID id) {
        UUID owner = currentUser.requireId();
        SessionRow session = requireOpen(id, owner);
        List<QuestionRow> questions = repository.questions(id);
        Summary summary = summarize(questions);
        repository.complete(id, summary.text(), summary.average(), now());
        return detail(session.id(), owner);
    }

    // ------------------------------------------------------------------ evaluation

    private QuestionResponse evaluate(SessionRow session, int position, UUID owner) {
        QuestionRow question = requireQuestion(session, position);
        // The grounding data is read in a short transaction; the provider is called outside it.
        InterviewEvaluator.Context context = readOnly.execute(status -> {
            List<String> jobSkills = session.jobId() == null ? List.<String>of()
                    : jobRepository.findDetailById(session.jobId())
                    .map(job -> job.getSkills().stream().map(Skill::getName).sorted().toList()).orElse(List.of());
            List<String> resumeSkills = session.resumeId() == null ? List.<String>of() : resumeSkillsOf(session.resumeId());
            return new InterviewEvaluator.Context(session.jobTitle(), session.companyName(), jobSkills, resumeSkills,
                    question.category(), question.focus(), question.question(), question.answer(), session.interviewType(),
                    session.difficulty());
        });
        InterviewEvaluator.Outcome outcome = evaluator.evaluate(context);
        String note = null;
        if (outcome.evaluation().isPresent()) {
            InterviewEvaluator.Evaluation evaluation = outcome.evaluation().get();
            repository.saveEvaluation(session.id(), position, evaluation.relevance(), evaluation.completeness(),
                    evaluation.clarity(), evaluation.technicalCorrectness(), evaluation.communication(),
                    String.join("\n", evaluation.strengths()), String.join("\n", evaluation.improvements()),
                    evaluation.suggestedApproach(), now());
        } else {
            repository.markUnavailable(session.id(), position);
            note = outcome.unavailableReason();
        }
        QuestionRow updated = requireQuestion(session, position);
        return question(updated, note);
    }

    private List<String> resumeSkillsOf(UUID resumeId) {
        try {
            return resumeService.requireCompletedResume(resumeId).getSkills().stream().map(Skill::getName).sorted().toList();
        } catch (ResourceNotFoundException removed) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ summary

    record Summary(String text, BigDecimal average) {
    }

    static Summary summarize(List<QuestionRow> questions) {
        List<QuestionRow> evaluated = questions.stream().filter(q -> "EVALUATED".equals(q.feedbackStatus())).toList();
        long answered = questions.stream().filter(q -> q.answer() != null).count();
        long skipped = questions.stream().filter(q -> "SKIPPED".equals(q.feedbackStatus())).count();
        String skippedNote = skipped == 0 ? "" : " You skipped " + skipped + ".";
        if (evaluated.isEmpty()) {
            return new Summary("You answered " + answered + " of " + questions.size() + " questions. No answer was evaluated, "
                    + "so there is no score for this session." + skippedNote, null);
        }
        double average = evaluated.stream().mapToDouble(InterviewService::scoreOf).average().orElse(0);
        Map<String, List<Double>> byCategory = new LinkedHashMap<>();
        evaluated.forEach(q -> byCategory.computeIfAbsent(q.category(), key -> new ArrayList<>()).add(scoreOf(q)));
        Map<String, Double> categoryAverage = new LinkedHashMap<>();
        byCategory.forEach((category, scores) ->
                categoryAverage.put(category, scores.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        String strongest = categoryAverage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        String weakest = categoryAverage.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        List<String> focus = evaluated.stream().sorted(Comparator.comparingDouble(InterviewService::scoreOf))
                .map(QuestionRow::improvements).filter(text -> text != null && !text.isBlank())
                .map(text -> text.split("\n")[0]).distinct().limit(3).toList();

        StringBuilder text = new StringBuilder();
        text.append("You answered ").append(answered).append(" of ").append(questions.size()).append(" questions; ")
                .append(evaluated.size()).append(" were evaluated, averaging ").append(format(average)).append(" out of 5.")
                .append(skippedNote);
        if (categoryAverage.size() > 1 && !strongest.equals(weakest)) {
            text.append(" Strongest: ").append(label(strongest)).append(" (").append(format(categoryAverage.get(strongest)))
                    .append("). Needs most work: ").append(label(weakest)).append(" (")
                    .append(format(categoryAverage.get(weakest))).append(").");
        }
        if (!focus.isEmpty()) {
            text.append(" Focus next: ").append(String.join(" ", focus));
        }
        String summary = text.length() > 2000 ? text.substring(0, 2000) : text.toString();
        return new Summary(summary, BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP));
    }

    private static double scoreOf(QuestionRow q) {
        return Stream.of(q.relevance(), q.completeness(), q.clarity(), q.technicalCorrectness(), q.communication())
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).average().orElse(0);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String label(String category) {
        return switch (category) {
            case "TECHNICAL" -> "technical questions";
            case "ROLE" -> "role questions";
            case "RESUME" -> "resume questions";
            default -> "behavioral questions";
        };
    }

    // ------------------------------------------------------------------ lookups and mapping

    private SessionRow requireOpen(UUID id, UUID owner) {
        SessionRow session = repository.find(id, owner).orElseThrow(() -> ResourceNotFoundException.of("Interview session", id));
        if (!"IN_PROGRESS".equals(session.status())) {
            throw new InvalidRequestException("This interview session is already completed");
        }
        return session;
    }

    private QuestionRow requireQuestion(SessionRow session, int position) {
        return repository.questions(session.id()).stream().filter(q -> q.position() == position).findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Interview question", position));
    }

    private static void requireAttemptsLeft(QuestionRow question) {
        if (question.evaluationAttempts() >= MAX_EVALUATIONS) {
            throw new InvalidRequestException("This question has been evaluated " + MAX_EVALUATIONS
                    + " times; move on to the next one");
        }
    }

    private SessionResponse detail(UUID id, UUID owner) {
        SessionRow row = repository.find(id, owner).orElseThrow(() -> ResourceNotFoundException.of("Interview session", id));
        List<QuestionRow> questions = repository.questions(id);
        Report report = "COMPLETED".equals(row.status()) ? report(questions) : null;
        return response(row, questions.stream().map(q -> question(q, null)).toList(), report);
    }

    private static SessionResponse response(SessionRow row, List<QuestionResponse> questions) {
        return response(row, questions, null);
    }

    private static SessionResponse response(SessionRow row, List<QuestionResponse> questions, Report report) {
        return new SessionResponse(row.id(), row.jobId(), row.jobTitle(), row.companyName(), row.resumeId(), row.status(),
                row.createdAt(), row.completedAt(), row.summary(), row.averageScore(), row.answered(), row.evaluated(),
                row.total(), questions, row.interviewType(), row.difficulty(), row.skipped(), report);
    }

    // ------------------------------------------------------------------ V9.6 report

    /** Technical questions are TECHNICAL and RESUME ones; behavioral are BEHAVIORAL and ROLE ones. */
    static boolean isTechnical(String category) {
        return "TECHNICAL".equals(category) || "RESUME".equals(category);
    }

    private Report report(List<QuestionRow> questions) {
        Report scores = scores(questions);
        return new Report(scores.overallScore(), scores.technicalScore(), scores.behavioralScore(), scores.strongAreas(),
                scores.weakAreas(), scores.prepareTopics(), learningFor(scores.prepareTopics()));
    }

    /** Everything in the report except the learning plan; a strong answer scores 4 or more, a weak one under 3. */
    public static Report scores(List<QuestionRow> questions) {
        List<QuestionRow> evaluated = questions.stream().filter(q -> "EVALUATED".equals(q.feedbackStatus())).toList();
        List<String> strong = evaluated.stream().filter(q -> scoreOf(q) >= 4).map(InterviewService::area).distinct().toList();
        List<String> weak = evaluated.stream().filter(q -> scoreOf(q) < 3).map(InterviewService::area).distinct().toList();
        List<String> prepare = questions.stream()
                .filter(q -> q.focus() != null)
                .filter(q -> "SKIPPED".equals(q.feedbackStatus()) || q.answer() == null
                        || ("EVALUATED".equals(q.feedbackStatus()) && scoreOf(q) < 3))
                .map(QuestionRow::focus).distinct().limit(8).toList();
        return new Report(average(evaluated), average(evaluated.stream().filter(q -> isTechnical(q.category())).toList()),
                average(evaluated.stream().filter(q -> !isTechnical(q.category())).toList()), strong, weak, prepare, null);
    }

    private static Double average(List<QuestionRow> rows) {
        return rows.isEmpty() ? null
                : Math.round(rows.stream().mapToDouble(InterviewService::scoreOf).average().orElse(0) * 10.0) / 10.0;
    }

    private static String area(QuestionRow q) {
        return q.focus() != null ? q.focus() : label(q.category());
    }

    /** Topics to prepare that the learning plan already has, or that are priority skills on its roadmap. */
    private List<LearningSuggestion> learningFor(List<String> topics) {
        if (topics.isEmpty()) {
            return List.of();
        }
        LearningDtos.Plan plan;
        try {
            plan = learning.plan();
        } catch (RuntimeException unavailable) {
            log.debug("Learning plan unavailable for the interview report: {}", unavailable.getClass().getSimpleName());
            return List.of();
        }
        List<LearningSuggestion> suggestions = new ArrayList<>();
        for (String topic : topics) {
            plan.items().stream().filter(item -> item.skill().equalsIgnoreCase(topic)).findFirst().ifPresentOrElse(
                    item -> suggestions.add(new LearningSuggestion(item.skill(), "PLAN_ITEM", item.id(), item.skillId(),
                            item.status(), "Already in your learning plan: " + item.topic())),
                    () -> plan.priorities().stream().filter(priority -> priority.skill().equalsIgnoreCase(topic)).findFirst()
                            .ifPresent(priority -> suggestions.add(new LearningSuggestion(priority.skill(), "ROADMAP_PRIORITY",
                                    null, priority.skillId(), null, priority.reason()))));
        }
        return suggestions;
    }

    private static QuestionResponse question(QuestionRow q, String note) {
        Feedback feedback = "EVALUATED".equals(q.feedbackStatus())
                ? new Feedback(q.relevance(), q.completeness(), q.clarity(), q.technicalCorrectness(),
                Math.round(scoreOf(q) * 10.0) / 10.0, lines(q.strengths()), lines(q.improvements()), q.evaluatedAt(),
                q.communication(), q.suggestedApproach())
                : null;
        String feedbackNote = note != null ? note
                : "UNAVAILABLE".equals(q.feedbackStatus()) ? "Feedback is not available for this answer yet; try again." : null;
        return new QuestionResponse(q.position(), q.category(), q.question(), q.focus(), q.answer(), q.answeredAt(),
                q.feedbackStatus(), feedback, feedbackNote, q.evaluationAttempts(), q.skippedAt());
    }

    private static List<String> lines(String text) {
        return text == null || text.isBlank() ? List.of() : Arrays.stream(text.split("\n")).filter(s -> !s.isBlank()).toList();
    }

    private static String experience(Job job) {
        Short min = job.getExperienceMin();
        Short max = job.getExperienceMax();
        if (min == null && max == null) {
            return null;
        }
        return min == null ? "up to " + max + " years" : max == null ? min + "+ years" : min + "–" + max + " years";
    }

    private static List<String> names(List<SkillResponse> skills) {
        return skills.stream().map(SkillResponse::name).toList();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
