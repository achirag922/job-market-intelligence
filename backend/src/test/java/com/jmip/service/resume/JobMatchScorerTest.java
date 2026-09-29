package com.jmip.service.resume;

import com.jmip.dto.resume.MatchBreakdown;
import com.jmip.dto.resume.MatchBreakdown.Status;
import com.jmip.dto.resume.MatchPreferences;
import com.jmip.entity.Job;
import com.jmip.entity.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** V8.3: each dimension's rule, missing data, and how the overall score is weighted. */
class JobMatchScorerTest {

    private final JobMatchScorer scorer = new JobMatchScorer();

    @Test
    @DisplayName("with no preferences the overall score is exactly the skill match, everything else unavailable")
    void skillsOnly() {
        MatchBreakdown breakdown = scorer.score(1, 3, job(null, null, null, null, null, null, null), MatchPreferences.NONE);

        assertThat(breakdown.overallPercentage()).isEqualTo(33.3);
        assertThat(breakdown.skills().status()).isEqualTo(Status.PARTIAL);
        assertThat(breakdown.skills().detail()).isEqualTo("1 of 3 required skills on your resume");
        assertThat(java.util.List.of(breakdown.experience(), breakdown.location(), breakdown.workMode(), breakdown.salary()))
                .allSatisfy(d -> {
                    assertThat(d.status()).isEqualTo(Status.UNAVAILABLE);
                    assertThat(d.score()).isNull();
                });
    }

    @Test
    @DisplayName("a posting with no skills has no overall score")
    void noJobSkills() {
        MatchBreakdown breakdown = scorer.score(0, 0, job(null, null, null, null, null, null, null), prefs(5, null, null, null, null));
        assertThat(breakdown.overallPercentage()).isNull();
        assertThat(breakdown.skills().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("experience: in range, short by years, above range, or not stated")
    void experience() {
        Job midLevel = job(null, null, (short) 3, (short) 6, null, null, null);
        assertThat(scorer.experience(midLevel, 4).status()).isEqualTo(Status.MATCH);
        assertThat(scorer.experience(midLevel, 2).score()).isEqualTo(75.0);
        assertThat(scorer.experience(midLevel, 2).detail()).isEqualTo("2 years is 1 year short of the 3–6 years asked");
        assertThat(scorer.experience(midLevel, 0).score()).isEqualTo(25.0);
        assertThat(scorer.experience(job(null, null, (short) 5, null, null, null, null), 0).status()).isEqualTo(Status.NO_MATCH);
        assertThat(scorer.experience(midLevel, 9).score()).isEqualTo(75.0);
        assertThat(scorer.experience(job(null, null, null, null, null, null, null), 4).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(scorer.experience(midLevel, null).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("location: city, a whole country, same country other city, elsewhere, or no location")
    void location() {
        Location berlin = location("Berlin", null, "Germany");
        assertThat(scorer.location(berlin, "berlin").status()).isEqualTo(Status.MATCH);
        assertThat(scorer.location(berlin, "Germany").status()).isEqualTo(Status.MATCH);
        assertThat(scorer.location(berlin, "Munich, Germany").score()).isEqualTo(50.0);
        assertThat(scorer.location(berlin, "Paris, France").status()).isEqualTo(Status.NO_MATCH);
        assertThat(scorer.location(null, "Berlin").status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(scorer.location(berlin, null).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("work mode: read from the posting's words with the market rule; unstated is unavailable")
    void workMode() {
        assertThat(scorer.workMode("Fully remote team.", "REMOTE").status()).isEqualTo(Status.MATCH);
        assertThat(scorer.workMode("Hybrid, two days in the office.", "REMOTE").score()).isEqualTo(50.0);
        assertThat(scorer.workMode("Work on-site in Berlin.", "REMOTE").status()).isEqualTo(Status.NO_MATCH);
        assertThat(scorer.workMode("Build services.", "REMOTE").status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(scorer.workMode("Fully remote team.", null).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(JobMatchScorer.workModeOf("Remote-friendly, hybrid possible")).isEqualTo("HYBRID");
    }

    @Test
    @DisplayName("salary: compared only in the same currency, against the top of the stated range")
    void salary() {
        Job paid = job(null, null, null, null, new BigDecimal("50000"), new BigDecimal("70000"), "EUR");
        assertThat(scorer.salary(paid, new BigDecimal("60000"), "EUR").status()).isEqualTo(Status.MATCH);
        assertThat(scorer.salary(paid, new BigDecimal("80000"), "EUR").status()).isEqualTo(Status.NO_MATCH);
        assertThat(scorer.salary(paid, new BigDecimal("60000"), "USD").status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(scorer.salary(paid, new BigDecimal("60000"), "USD").detail()).contains("not converted");
        assertThat(scorer.salary(job(null, null, null, null, null, null, null), new BigDecimal("1"), "EUR").status())
                .isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("the overall score is the weighted average of the available dimensions only")
    void weighting() {
        Job job = job(location("Berlin", null, "Germany"), "Remote role.", (short) 2, (short) 4, null, null, null);
        MatchBreakdown breakdown = scorer.score(1, 2, job, prefs(3, "Paris", "REMOTE", null, null));

        // skills 50*60 + experience 100*15 + location 0*10 + work mode 100*10 = 5500 over 95; salary unavailable
        assertThat(breakdown.overallPercentage()).isEqualTo(57.9);
        assertThat(breakdown.salary().status()).isEqualTo(Status.UNAVAILABLE);
    }

    private static MatchPreferences prefs(Integer years, String location, String mode, BigDecimal salary, String currency) {
        return new MatchPreferences(years, location, mode, salary, currency);
    }

    private static Job job(Location location, String description, Short minYears, Short maxYears,
                           BigDecimal minSalary, BigDecimal maxSalary, String currency) {
        Job job = instance(Job.class);
        set(job, "location", location);
        set(job, "description", description);
        set(job, "experienceMin", minYears);
        set(job, "experienceMax", maxYears);
        set(job, "salaryMin", minSalary);
        set(job, "salaryMax", maxSalary);
        set(job, "currency", currency);
        return job;
    }

    private static Location location(String city, String state, String country) {
        Location location = instance(Location.class);
        set(location, "city", city);
        set(location, "state", state);
        set(location, "country", country);
        return location;
    }

    private static <T> T instance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
