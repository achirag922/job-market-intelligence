package com.jmip.etl.transform;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Prepares a job description for skill extraction and classification.
 *
 * <p>Job descriptions arrive as scraped HTML fragments, bullet lists and wrapped
 * paragraphs. Left alone, that formatting hides skills: a description writing
 * "Spring&#160;Boot" with a non-breaking space, or splitting "Kuber-\nnetes" across a line,
 * would not match anything.
 *
 * <p>What it does <em>not</em> do matters as much. Nothing is lower-cased, no punctuation
 * is stripped and no words are removed. Matching is already case-insensitive, and
 * "Node.js", "C#" and "C++" all lose their identity the moment punctuation goes. Line
 * structure is kept too, because descriptions are lists and flattening them would run the
 * end of one bullet into the start of the next, inventing phrases nobody wrote.
 *
 * <p>The original description is never modified; this produces a working copy used for
 * extraction only.
 */
@Component
public class JobDescriptionProcessor {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_BREAK =
            Pattern.compile("<\\s*(br|/p|/li|/div|/h[1-6])\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_SPACE =
            Pattern.compile("&nbsp;|&#160;|&#xa0;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ENTITY_AMP = Pattern.compile("&amp;", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOFT_HYPHEN_LINE_BREAK = Pattern.compile("\\u00AD\\s*\\R\\s*");
    private static final Pattern BULLETS =
            Pattern.compile("[\\u2022\\u2023\\u25AA\\u25CF\\u25E6\\u00B7\\u2043\\u2219]");
    private static final Pattern HORIZONTAL_WHITESPACE =
            Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2007\\u202F\\u2009\\u200A]+");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("(\\R\\s*){3,}");

    /**
     * @param description the stored description, which may be null or blank
     * @return text ready for extraction, never null; an empty string when there was
     *         nothing to process
     */
    public String process(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }

        // Compatibility normalisation folds ligatures and exotic spacing into plain
        // equivalents, so a typeset description matches the same terms as a plain one.
        String text = Normalizer.normalize(description, Normalizer.Form.NFKC);

        // Block-level tags become line breaks before all tags are stripped, so list items
        // stay on separate lines instead of running together.
        text = HTML_BREAK.matcher(text).replaceAll("\n");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = HTML_ENTITY_SPACE.matcher(text).replaceAll(" ");
        text = HTML_ENTITY_AMP.matcher(text).replaceAll("&");

        // Rejoin words the layout split across lines, before line breaks are tidied.
        text = SOFT_HYPHEN_LINE_BREAK.matcher(text).replaceAll("");
        text = BULLETS.matcher(text).replaceAll(" ");
        text = HORIZONTAL_WHITESPACE.matcher(text).replaceAll(" ");
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");

        return text.lines()
                .map(String::strip)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("")
                .strip();
    }
}
