package com.jmip.service.interview;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V8.7: interview questions from the job's and the resume's own data, deterministically. No AI
 * is involved, so preparation works even when the provider is down, and nothing is invented:
 * a question names only skills and terms that the posting or the resume actually contains, and
 * a skill missing from the resume is asked about honestly, never presented as experience.
 */
@Component
public class InterviewQuestionGenerator {

    static final int MAX_QUESTIONS = 8;

    /** One question, what kind it is and what it is about. */
    public record Question(String category, String text, String focus) {
    }

    /**
     * @param matched    job skills the resume shows
     * @param missing    job skills the resume does not show
     * @param resumeOnly resume skills the job does not list
     * @param terms      the posting's own frequent terms (V8.6), skills excluded
     * @param experience the posting's experience requirement in words, or null
     * @param hasResume  false when the user has no processed resume: skills are then asked about neutrally
     */
    public List<Question> generate(String jobTitle, String company, List<String> matched, List<String> missing,
                                   List<String> resumeOnly, List<String> terms, String experience, boolean hasResume) {
        List<Question> questions = new ArrayList<>();
        if (hasResume) {
            matched.stream().limit(2).forEach(skill -> questions.add(new Question("TECHNICAL",
                    "This role asks for " + skill + ", which is on your resume. Describe a specific problem you solved with "
                            + skill + ": the situation, what you did and the result.", skill)));
            missing.stream().limit(1).forEach(skill -> questions.add(new Question("TECHNICAL",
                    "The posting lists " + skill + ", which your resume does not mention. If you have used it, explain how; "
                            + "if not, say honestly how you would get up to speed and which related experience would help.", skill)));
        } else {
            missing.stream().limit(2).forEach(skill -> questions.add(new Question("TECHNICAL",
                    "The posting lists " + skill + ". How have you used it, and what should an interviewer know about your level?",
                    skill)));
        }
        if (questions.isEmpty() && !terms.isEmpty()) {
            questions.add(new Question("TECHNICAL", "The posting mentions " + terms.get(0) + ". What does good work in that "
                    + "area look like to you, and how have you done it?", terms.get(0)));
        }

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

        if (!resumeOnly.isEmpty()) {
            String skill = resumeOnly.get(0);
            questions.add(new Question("RESUME", "Your resume lists " + skill + ", which this posting does not ask for. "
                    + "How would that experience still help you in this role?", skill));
        } else if (hasResume) {
            questions.add(new Question("RESUME", "Pick the piece of work on your resume that is most relevant to this role "
                    + "and walk through it: your part, the decisions you made and the outcome.", null));
        }

        questions.add(new Question("BEHAVIORAL", "Tell me about a time you had to learn something new quickly to deliver. "
                + "What did you do, and what was the result?", null));
        questions.add(new Question("BEHAVIORAL", "Describe a disagreement with a teammate about how to do a piece of work. "
                + "How was it resolved, and what did you learn?", null));
        return questions.size() <= MAX_QUESTIONS ? questions : questions.subList(0, MAX_QUESTIONS);
    }
}
