package com.jmip.ai;

/**
 * The headings that separate the parts of an intent-extraction message.
 *
 * <p>They exist because the message carries two things with different standing: the
 * question being asked, and the previous turn supplied as background. A provider has to be
 * able to tell them apart — the previous question is context, not a second question, and
 * treating it as one means a follow-up quietly gets answered as though it were the question
 * before it.
 *
 * <p>Kept here rather than inline so the side that writes the message and any side that
 * reads it agree by construction.
 */
public final class PromptMarkers {

    /** Everything after this heading is the question to answer. */
    public static final String QUESTION = "QUESTION:";

    /** Everything after this heading, up to {@link #QUESTION}, is background. */
    public static final String PREVIOUS_TURN = "PREVIOUS TURN (context only, not a question to answer):";

    private PromptMarkers() {
    }

    /**
     * The question alone, with any context section stripped.
     *
     * <p>Returns the whole message when there is no heading, so a caller that was handed
     * plain text still gets something sensible rather than nothing.
     */
    public static String questionOf(String userMessage) {
        if (userMessage == null) {
            return "";
        }
        int start = userMessage.lastIndexOf(QUESTION);
        return start < 0 ? userMessage : userMessage.substring(start + QUESTION.length()).trim();
    }
}
