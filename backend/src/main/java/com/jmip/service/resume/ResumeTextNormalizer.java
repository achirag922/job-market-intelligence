package com.jmip.service.resume;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Tidies extracted PDF text without throwing away meaning.
 *
 * <p>PDF extraction produces ragged output: bullet glyphs, non-breaking spaces, soft
 * hyphens where a word was split across lines, and a line break after every visual line.
 * Left alone, those break skill matching — a resume listing "Spring&#160;Boot" with a
 * non-breaking space would not match "Spring Boot".
 *
 * <p>Line structure is kept. Resumes are lists, and collapsing everything onto one line
 * would run the end of one bullet into the start of the next, inventing word pairs that
 * were never written.
 */
@Component
public class ResumeTextNormalizer {

    private static final Pattern SOFT_HYPHEN_LINE_BREAK = Pattern.compile("\\u00AD\\s*\\R\\s*");
    private static final Pattern BULLETS = Pattern.compile("[\\u2022\\u2023\\u25AA\\u25CF\\u00B7\\u2043]");
    private static final Pattern HORIZONTAL_WHITESPACE =
            Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2007\\u202F\\u2009\\u200A]+");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("(\\R\\s*){3,}");

    public String normalize(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        // Compatibility normalisation folds ligatures and exotic spacing into their plain
        // equivalents, so "ﬁnance" and "finance" are the same word to the matcher.
        String text = Normalizer.normalize(rawText, Normalizer.Form.NFKC);

        // Rejoin words the layout split across lines, before line breaks become spaces.
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
