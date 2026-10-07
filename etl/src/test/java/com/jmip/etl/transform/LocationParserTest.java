package com.jmip.etl.transform;

import com.jmip.etl.transform.LocationParser.ParsedLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** V10.9: free-text locations become city / state / country without inventing places. */
class LocationParserTest {

    private final LocationParser parser = new LocationParser();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "Remote", "WORK FROM HOME", "n/a", "Worldwide", " , "})
    @DisplayName("blank or remote-style text means no location, not a country called Remote")
    void noLocation(String raw) {
        assertThat(parser.parse(raw)).isEqualTo(ParsedLocation.NONE);
        assertThat(parser.parse(raw).isPresent()).isFalse();
    }

    @Test
    @DisplayName("one, two and three parts are country, city + country, city + state + country")
    void partCounts() {
        assertThat(parser.parse("Germany")).isEqualTo(new ParsedLocation(null, null, "Germany"));
        assertThat(parser.parse(" Berlin , Germany ")).isEqualTo(new ParsedLocation("Berlin", null, "Germany"));
        assertThat(parser.parse("Austin, Texas, United States")).isEqualTo(new ParsedLocation("Austin", "Texas", "United States"));
    }

    @Test
    @DisplayName("longer addresses keep the last two parts as state and country; remote markers inside are dropped")
    void longAndMixed() {
        assertThat(parser.parse("Building 4, Elm Street, Austin, Texas, United States"))
                .isEqualTo(new ParsedLocation("Building 4, Elm Street, Austin", "Texas", "United States"));
        assertThat(parser.parse("Remote, Berlin, Germany")).isEqualTo(new ParsedLocation("Berlin", null, "Germany"));
    }
}
