package com.jmip.service.assistant;

/**
 * Either a question the assistant can act on, or the reason it cannot.
 *
 * <p>A rejection is an ordinary outcome, not an error. "I could not find the skill
 * Cobol", "that question is outside this dataset" and "tell me which job to compare
 * against" are all useful answers, and none of them is an exception.
 *
 * @param intent    the validated intent, null when the question was rejected
 * @param rejection a sentence for the user, null when the question was accepted
 */
public record IntentValidation(ResolvedIntent intent, String rejection) {

    public static IntentValidation accepted(ResolvedIntent intent) {
        return new IntentValidation(intent, null);
    }

    public static IntentValidation rejected(String reason) {
        return new IntentValidation(null, reason);
    }

    public boolean isAccepted() {
        return intent != null;
    }
}
