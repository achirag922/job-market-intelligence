package com.jmip.service.interview;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V8.7: interview questions from the job's and the resume's own data, deterministically. No AI
 * is involved, so preparation works even when the provider is down, and nothing is invented:
 * a question names only skills and terms that the posting or the resume actually contains, and
 * a skill missing from the resume is asked about honestly, never presented as experience.
 *
 * <p>V9.6: a session also has an interview type (TECHNICAL, BEHAVIORAL or MIXED), a difficulty
 * (EASY, MEDIUM or HARD) that changes how deep each question goes, and a question count.
 */
@Component
public class InterviewQuestionGenerator {

    static final int MAX_QUESTIONS = 8;
    public static final int MIN_COUNT = 3;
    public static final int MAX_COUNT = 10;
    static final int DEFAULT_COUNT = 6;

    /** One question, what kind it is and what it is about. */
    public record Question(String category, String text, String focus) {
    }

    /**
     * @param type       TECHNICAL, BEHAVIORAL or MIXED
     * @param difficulty EASY, MEDIUM or HARD
     * @param count      how many questions; absent keeps the V8.7 set for MIXED and six otherwise
     */
    public record Setup(String type, String difficulty, Integer count) {
        public static final Setup DEFAULT = new Setup("MIXED", "MEDIUM", null);
    }

    private static final List<String> BEHAVIORAL_BANK = List.of(
            "Tell me about a time you had to learn something new quickly to deliver. What did you do, and what was the result?",
            "Describe a disagreement with a teammate about how to do a piece of work. How was it resolved, and what did you learn?",
            "Tell me about a mistake you made at work. How did you handle it, and what changed afterwards?",
            "Describe a time you had to deliver under a tight deadline. How did you decide what to do first?",
            "Tell me about a piece of work you are proud of. What exactly was your part in it?",
            "Describe a time you received critical feedback. What did you change as a result?");

    /** V8.7 behaviour: a mixed set of medium questions, at most eight. */
    public List<Question> generate(String jobTitle, String company, List<String> matched, List<String> missing,
                                   List<String> resumeOnly, List<String> terms, String experience, boolean hasResume) {
        return generate(Setup.DEFAULT, jobTitle, company, matched, missing, resumeOnly, terms, experience, hasResume);
    }

    /**
     * @param matched    job skills the resume shows
     * @param missing    job skills the resume does not show
     * @param resumeOnly resume skills the job does not list
     * @param terms      the posting's own frequent terms (V8.6), skills excluded
     * @param experience the posting's experience requirement in words, or null
     * @param hasResume  false when the user has no processed resume: skills are then asked about neutrally
     */
    public List<Question> generate(Setup setup, String jobTitle, String company, List<String> matched, List<String> missing,
                                   List<String> resumeOnly, List<String> terms, String experience, boolean hasResume) {
        String difficulty = setup.difficulty() == null ? "MEDIUM" : setup.difficulty();
        List<Question> technical = technical(difficulty, jobTitle, matched, missing, resumeOnly, terms, hasResume);
        List<Question> behavioral = behavioral(difficulty, jobTitle, company, terms, experience);
        String type = setup.type() == null ? "MIXED" : setup.type();

        if ("MIXED".equals(type) && setup.count() == null) {
            // The V8.7 order: up to three skill questions, the role, the resume, then two behavioral.
            List<Question> questions = new ArrayList<>(technical.stream().filter(q -> "TECHNICAL".equals(q.category()))
                    .limit(technicalLimit(matched, missing, hasResume, terms)).toList());
            behavioral.stream().filter(q -> "ROLE".equals(q.category())).forEach(questions::add);
            technical.stream().filter(q -> "RESUME".equals(q.category())).forEach(questions::add);
            behavioral.stream().filter(q -> "BEHAVIORAL".equals(q.category())).limit(2).forEach(questions::add);
            return questions.size() <= MAX_QUESTIONS ? questions : questions.subList(0, MAX_QUESTIONS);
        }
        int count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, setup.count() == null ? DEFAULT_COUNT : setup.count()));
        return switch (type) {
            case "TECHNICAL" -> technical.subList(0, Math.min(count, technical.size()));
            case "BEHAVIORAL" -> behavioral.subList(0, Math.min(count, behavioral.size()));
            default -> interleave(technical, behavioral, count);
        };
    }

    /** How many skill questions the V8.7 set had: two known and one missing with a resume, two otherwise. */
    private static int technicalLimit(List<String> matched, List<String> missing, boolean hasResume, List<String> terms) {
        int skills = hasResume ? Math.min(2, matched.size()) + Math.min(1, missing.size()) : Math.min(2, missing.size());
        return skills == 0 && !terms.isEmpty() ? 1 : skills;
    }

    /** Skill questions (known skills first, then missing ones), a posting-term question, then the resume question. */
    private static List<Question> technical(String difficulty, String jobTitle, List<String> matched, List<String> missing,
                                            List<String> resumeOnly, List<String> terms, boolean hasResume) {
        List<Question> questions = new ArrayList<>();
        if (hasResume) {
            List<Question> known = matched.stream().map(skill -> knownSkill(skill, difficulty)).toList();
            List<Question> unknown = missing.stream().map(skill -> missingSkill(skill, difficulty)).toList();
            // V8.7 order first: two known, one missing; then the rest alternately.
            known.stream().limit(2).forEach(questions::add);
            unknown.stream().limit(1).forEach(questions::add);
            int k = Math.min(2, known.size());
            int u = Math.min(1, unknown.size());
            while (k < known.size() || u < unknown.size()) {
                if (u < unknown.size()) {
                    questions.add(unknown.get(u++));
                }
                if (k < known.size()) {
                    questions.add(known.get(k++));
                }
            }
        } else {
            missing.forEach(skill -> questions.add(neutralSkill(skill, difficulty)));
        }
        if (!terms.isEmpty()) {
            questions.add(new Question("TECHNICAL", deeper("The posting mentions " + terms.get(0) + ". What does good work in "
                    + "that area look like to you, and how have you done it?", difficulty), terms.get(0)));
        }
        if (!resumeOnly.isEmpty()) {
            String skill = resumeOnly.get(0);
            questions.add(new Question("RESUME", "Your resume lists " + skill + ", which this posting does not ask for. "
                    + "How would that experience still help you in this role?", skill));
        } else if (hasResume) {
            questions.add(new Question("RESUME", "Pick the piece of work on your resume that is most relevant to this role "
                    + "and walk through it: your part, the decisions you made and the outcome.", null));
        }
        if (questions.isEmpty()) {
            questions.add(new Question("TECHNICAL", deeper("Walk through how you would approach the main technical work of a "
                    + jobTitle + ": where you would start and how you would check it works.", difficulty), null));
        }
        return questions;
    }

    /** Role questions from the posting alternating with the behavioral bank. */
    private static List<Question> behavioral(String difficulty, String jobTitle, String company, List<String> terms,
                                             String experience) {
        List<Question> questions = new ArrayList<>();
        List<String> roleTerms = terms.stream().limit(2).toList();
        if (!roleTerms.isEmpty()) {
            questions.add(new Question("ROLE", "The " + jobTitle + " role at " + company + " mentions "
                    + String.join(" and ", roleTerms) + ". How does your experience relate to that work?", roleTerms.get(0)));
        }
        questions.add(new Question("ROLE", "Why are you interested in the " + jobTitle + " role at " + company
                + ", and what would you focus on in your first months?", null));
        if (experience != null) {
            questions.add(new Question("ROLE", "The posting asks for " + experience + " of experience. Which of your roles "
                    + "best shows you are ready for this level, and why?", null));
        }
        List<Question> bank = BEHAVIORAL_BANK.stream().map(text -> new Question("BEHAVIORAL",
                "HARD".equals(difficulty) ? text + " Looking back, what would you do differently?" : text, null)).toList();
        return interleave(questions, bank, questions.size() + bank.size());
    }

    private static List<Question> interleave(List<Question> technical, List<Question> behavioral, int count) {
        List<Question> questions = new ArrayList<>();
        int t = 0;
        int b = 0;
        while (questions.size() < count && (t < technical.size() || b < behavioral.size())) {
            if (t < technical.size()) {
                questions.add(technical.get(t++));
            }
            if (questions.size() < count && b < behavioral.size()) {
                questions.add(behavioral.get(b++));
            }
        }
        return questions;
    }

    private static Question knownSkill(String skill, String difficulty) {
        String text = "EASY".equals(difficulty)
                ? "This role asks for " + skill + ", which is on your resume. What have you used " + skill
                + " for, and why was it a good fit?"
                : deeper("This role asks for " + skill + ", which is on your resume. Describe a specific problem you solved with "
                + skill + ": the situation, what you did and the result.", difficulty);
        return new Question("TECHNICAL", text, skill);
    }

    private static Question missingSkill(String skill, String difficulty) {
        String text = "EASY".equals(difficulty)
                ? "The posting lists " + skill + ", which your resume does not mention. What do you know about it, "
                + "and how would you start learning it?"
                : deeper("The posting lists " + skill + ", which your resume does not mention. If you have used it, explain how; "
                + "if not, say honestly how you would get up to speed and which related experience would help.", difficulty);
        return new Question("TECHNICAL", text, skill);
    }

    private static Question neutralSkill(String skill, String difficulty) {
        String text = "EASY".equals(difficulty)
                ? "The posting lists " + skill + ". What have you used it for?"
                : deeper("The posting lists " + skill + ". How have you used it, and what should an interviewer know about "
                + "your level?", difficulty);
        return new Question("TECHNICAL", text, skill);
    }

    /** HARD questions also ask for trade-offs, risks and how the result was measured. */
    private static String deeper(String text, String difficulty) {
        return "HARD".equals(difficulty)
                ? text + " Then go deeper: which trade-offs did you weigh, what could go wrong, and how would you measure success?"
                : text;
    }
}
