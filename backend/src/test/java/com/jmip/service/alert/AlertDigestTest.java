package com.jmip.service.alert;

import com.jmip.entity.AlertFrequency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V8.4: the digest email's wording, and what it leaves out when data is missing. */
class AlertDigestTest {

    @Test
    @DisplayName("lists each job with company, location, work mode, match score and link")
    void composes() {
        AlertDigest digest = AlertDigest.compose("Jane", "Java in Berlin", AlertFrequency.WEEKLY, List.of(
                new AlertDigest.Item(7, "Java Developer", "Acme", "Berlin, Germany", "Hybrid", 72.4),
                new AlertDigest.Item(9, "Backend Engineer", "Beta", null, null, null)), "https://jmip.example/");

        assertThat(digest.subject()).isEqualTo("JMIP weekly digest: 2 new jobs for \"Java in Berlin\"");
        assertThat(digest.body())
                .startsWith("Hello Jane,")
                .contains("• Java Developer — Acme\n  Berlin, Germany · Hybrid\n  Match: 72% with your resume and preferences\n"
                        + "  https://jmip.example/jobs/7\n")
                .contains("• Backend Engineer — Beta\n  Location not stated\n  https://jmip.example/jobs/9\n")
                .contains("not a hiring prediction", "https://jmip.example/alerts");
    }

    @Test
    @DisplayName("a single job and no name read naturally")
    void singular() {
        AlertDigest digest = AlertDigest.compose(null, "Data", AlertFrequency.DAILY,
                List.of(new AlertDigest.Item(1, "Analyst", "Gamma", "Paris, France", "Remote", null)), "http://localhost:5173");
        assertThat(digest.subject()).isEqualTo("JMIP daily digest: 1 new job for \"Data\"");
        assertThat(digest.body()).startsWith("Hello,").doesNotContain("Match:");
    }
}
