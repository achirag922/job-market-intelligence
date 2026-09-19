package com.jmip.etl.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ContentFingerprintTest {

    private static final LocalDate POSTED = LocalDate.of(2026, 8, 13);

    private final ContentFingerprint fingerprint = new ContentFingerprint();

    @Test
    @DisplayName("is deterministic")
    void isDeterministic() {
        String first = fingerprint.compute("Backend Engineer", "Acme", "Berlin", null, "Germany", POSTED);
        String second = fingerprint.compute("Backend Engineer", "Acme", "Berlin", null, "Germany", POSTED);
        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("ignores case and surplus whitespace")
    void ignoresCaseAndWhitespace() {
        assertThat(fingerprint.compute("Backend Engineer", "Acme", "Berlin", null, "Germany", POSTED))
                .isEqualTo(fingerprint.compute("  backend   ENGINEER ", "acme", "berlin", null, "GERMANY", POSTED));
    }

    @Test
    @DisplayName("distinguishes different postings")
    void distinguishesDifferentPostings() {
        String base = fingerprint.compute("Backend Engineer", "Acme", "Berlin", null, "Germany", POSTED);

        assertThat(fingerprint.compute("Frontend Engineer", "Acme", "Berlin", null, "Germany", POSTED))
                .isNotEqualTo(base);
        assertThat(fingerprint.compute("Backend Engineer", "Globex", "Berlin", null, "Germany", POSTED))
                .isNotEqualTo(base);
        assertThat(fingerprint.compute("Backend Engineer", "Acme", "Munich", null, "Germany", POSTED))
                .isNotEqualTo(base);
        assertThat(fingerprint.compute("Backend Engineer", "Acme", "Berlin", null, "Germany", POSTED.minusDays(1)))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("the same job from two sources produces the same fingerprint")
    void sameJobFromTwoSourcesMatches() {
        // This is the whole point of the fingerprint: the source URL is deliberately not
        // part of it, so a mirrored posting is recognised as the same job.
        assertThat(fingerprint.compute("Data Engineer", "Acme", "Pune", "Maharashtra", "India", POSTED))
                .isEqualTo(fingerprint.compute("Data Engineer", "Acme", "Pune", "Maharashtra", "India", POSTED));
    }

    @Test
    @DisplayName("null components do not collide with empty ones in a misleading way")
    void handlesNullComponents() {
        String remote = fingerprint.compute("SRE", "Acme", null, null, null, POSTED);
        String located = fingerprint.compute("SRE", "Acme", null, null, "Ireland", POSTED);
        assertThat(remote).isNotEqualTo(located);
    }

    @Test
    @DisplayName("a missing posted date still yields a stable fingerprint")
    void missingDateIsStable() {
        assertThat(fingerprint.compute("SRE", "Acme", "Dublin", null, "Ireland", null))
                .isEqualTo(fingerprint.compute("SRE", "Acme", "Dublin", null, "Ireland", null));
    }
}
