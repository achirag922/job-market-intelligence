package com.jmip.etl.transform;

import com.jmip.etl.transform.SalaryParser.SalaryRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalaryParserTest {

    private final SalaryParser parser = new SalaryParser();

    @Test
    @DisplayName("reads a plain range with a trailing ISO code")
    void plainRangeWithIsoCode() {
        SalaryRange range = parser.parse("120000-150000 USD");
        assertThat(range.min()).isEqualByComparingTo("120000");
        assertThat(range.max()).isEqualByComparingTo("150000");
        assertThat(range.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("reads currency symbols and thousands separators")
    void symbolsAndSeparators() {
        SalaryRange range = parser.parse("$120,000 - $150,000");
        assertThat(range.min()).isEqualByComparingTo("120000");
        assertThat(range.max()).isEqualByComparingTo("150000");
        assertThat(range.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("expands a k suffix")
    void expandsThousandsSuffix() {
        SalaryRange range = parser.parse("£75k");
        assertThat(range.min()).isEqualByComparingTo("75000");
        assertThat(range.currency()).isEqualTo("GBP");
    }

    @Test
    @DisplayName("an open ended minimum leaves the maximum absent")
    void openEndedMinimum() {
        SalaryRange range = parser.parse("from 90000 EUR");
        assertThat(range.min()).isEqualByComparingTo("90000");
        assertThat(range.max()).isNull();
        assertThat(range.currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("an upper bound only leaves the minimum absent")
    void upperBoundOnly() {
        SalaryRange range = parser.parse("up to 60000 EUR");
        assertThat(range.min()).isNull();
        assertThat(range.max()).isEqualByComparingTo("60000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Competitive", "negotiable", "DOE", "Not disclosed", "n/a"})
    @DisplayName("undisclosed salaries are absent rather than invalid")
    void undisclosedIsNotAnError(String raw) {
        assertThat(parser.parse(raw)).isEqualTo(SalaryRange.NONE);
    }

    @Test
    @DisplayName("absent input yields an empty range")
    void absentInput() {
        assertThat(parser.parse(null)).isEqualTo(SalaryRange.NONE);
        assertThat(parser.parse("   ")).isEqualTo(SalaryRange.NONE);
    }

    @Test
    @DisplayName("an amount with no recognisable currency is rejected")
    void amountWithoutCurrencyIsRejected() {
        assertThatThrownBy(() -> parser.parse("120000-150000"))
                .isInstanceOf(ValueParseException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("an inverted range is rejected")
    void invertedRangeIsRejected() {
        assertThatThrownBy(() -> parser.parse("150000-120000 USD"))
                .isInstanceOf(ValueParseException.class)
                .hasMessageContaining("inverted");
    }

    @Test
    @DisplayName("Indian digit grouping is read correctly")
    void indianDigitGrouping() {
        SalaryRange range = parser.parse("₹18,00,000 - ₹25,00,000");
        assertThat(range.min()).isEqualByComparingTo("1800000");
        assertThat(range.max()).isEqualByComparingTo("2500000");
        assertThat(range.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("an ISO code is preferred over a bare symbol")
    void isoCodeWinsOverSymbol() {
        assertThat(parser.parse("$100000 CAD").currency()).isEqualTo("CAD");
    }

    @Test
    @DisplayName("equal bounds are a valid range")
    void equalBoundsAreValid() {
        SalaryRange range = parser.parse("100000-100000 USD");
        assertThat(range.min()).isEqualByComparingTo(BigDecimal.valueOf(100000));
        assertThat(range.max()).isEqualByComparingTo(BigDecimal.valueOf(100000));
    }
}
