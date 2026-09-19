package com.jmip.etl.transform;

import com.jmip.etl.transform.ExperienceParser.ExperienceRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperienceParserTest {

    private final ExperienceParser parser = new ExperienceParser();

    @ParameterizedTest
    @CsvSource({
            "'3-5 years', 3, 5",
            "'3 - 5 years', 3, 5",
            "'2 to 4 yrs', 2, 4",
            "'5–8 years', 5, 8"
    })
    @DisplayName("reads a range in its various spellings")
    void readsRange(String raw, int min, int max) {
        ExperienceRange range = parser.parse(raw);
        assertThat(range.min()).isEqualTo(min);
        assertThat(range.max()).isEqualTo(max);
    }

    @ParameterizedTest
    @CsvSource({
            "'5+ years', 5",
            "'at least 3 years', 3",
            "'minimum of 7 years', 7"
    })
    @DisplayName("an open ended minimum leaves the maximum absent")
    void openEndedMinimum(String raw, int min) {
        ExperienceRange range = parser.parse(raw);
        assertThat(range.min()).isEqualTo(min);
        assertThat(range.max()).isNull();
    }

    @Test
    @DisplayName("an upper bound only leaves the minimum absent")
    void upperBoundOnly() {
        ExperienceRange range = parser.parse("up to 4 years");
        assertThat(range.min()).isNull();
        assertThat(range.max()).isEqualTo(4);
    }

    @Test
    @DisplayName("a bare number is treated as an exact requirement")
    void bareNumber() {
        ExperienceRange range = parser.parse("6");
        assertThat(range.min()).isEqualTo(6);
        assertThat(range.max()).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Not specified", "n/a", "any", "fresher"})
    @DisplayName("unspecified experience is absent rather than invalid")
    void unspecifiedIsNotAnError(String raw) {
        assertThat(parser.parse(raw)).isEqualTo(ExperienceRange.NONE);
    }

    @Test
    @DisplayName("absent input yields an empty range")
    void absentInput() {
        assertThat(parser.parse(null)).isEqualTo(ExperienceRange.NONE);
        assertThat(parser.parse("  ")).isEqualTo(ExperienceRange.NONE);
    }

    @Test
    @DisplayName("an inverted range is rejected")
    void invertedRangeIsRejected() {
        assertThatThrownBy(() -> parser.parse("8-3 years"))
                .isInstanceOf(ValueParseException.class)
                .hasMessageContaining("inverted");
    }

    @Test
    @DisplayName("an implausible number of years is rejected")
    void implausibleValueIsRejected() {
        assertThatThrownBy(() -> parser.parse("99 years"))
                .isInstanceOf(ValueParseException.class)
                .hasMessageContaining("Implausible");
    }

    @Test
    @DisplayName("text with no number at all is rejected")
    void textWithoutNumberIsRejected() {
        assertThatThrownBy(() -> parser.parse("several years"))
                .isInstanceOf(ValueParseException.class)
                .hasMessageContaining("Unrecognised");
    }

    @Test
    @DisplayName("zero years is valid for an internship")
    void zeroYearsIsValid() {
        ExperienceRange range = parser.parse("0-1 years");
        assertThat(range.min()).isZero();
        assertThat(range.max()).isEqualTo(1);
    }
}
