package com.jmip.etl.transform;

import com.jmip.etl.config.ClassificationProperties;
import com.jmip.etl.config.SkillDictionaryProperties;
import com.jmip.etl.config.SkillDictionaryProperties.SkillDefinition;
import com.jmip.etl.model.TransformedJob;
import com.jmip.etl.raw.RawJobRecord;
import com.jmip.etl.validation.JobValidator;
import com.jmip.etl.validation.RecordRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobItemProcessorTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);

    private final JobItemProcessor processor = new JobItemProcessor(
            new TextNormalizer(),
            new LocationParser(),
            new ExperienceParser(),
            new SalaryParser(),
            new EmploymentTypeNormalizer(),
            new SkillExtractor(new SkillDictionaryProperties(List.of(
                    new SkillDefinition("Java", "LANGUAGE", List.of(), List.of()),
                    new SkillDefinition("Spring Boot", "FRAMEWORK", List.of(), List.of()),
                    new SkillDefinition("PostgreSQL", "DATABASE", List.of(), List.of())))),
            new JobDescriptionProcessor(),
            new JobClassifier(new ClassificationProperties(
                    new ClassificationProperties.Weights(5, 2, 1), 10,
                    List.of(new ClassificationProperties.Category("Backend Developer",
                            List.of("backend engineer"), List.of(), List.of("Java", "Spring Boot"))))),
            new ContentFingerprint(),
            new JobValidator(FIXED_CLOCK));

    @Test
    @DisplayName("transforms a complete record")
    void transformsCompleteRecord() {
        TransformedJob job = processor.process(raw().build());

        assertThat(job.title()).isEqualTo("Senior Backend Engineer");
        assertThat(job.companyName()).isEqualTo("Acme Systems");
        assertThat(job.city()).isEqualTo("Austin");
        assertThat(job.state()).isEqualTo("Texas");
        assertThat(job.country()).isEqualTo("United States");
        assertThat(job.employmentType()).isEqualTo("FULL_TIME");
        assertThat(job.experienceMin()).isEqualTo(5);
        assertThat(job.experienceMax()).isEqualTo(9);
        assertThat(job.salaryMin()).isEqualByComparingTo("150000");
        assertThat(job.salaryMax()).isEqualByComparingTo("190000");
        assertThat(job.currency()).isEqualTo("USD");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(job.contentFingerprint()).hasSize(64);
        assertThat(job.skills()).containsExactlyInAnyOrder("Java", "Spring Boot", "PostgreSQL");
    }

    @Test
    @DisplayName("trims surrounding whitespace")
    void trimsWhitespace() {
        TransformedJob job = processor.process(raw()
                .title("   Senior Backend Engineer   ")
                .company("  Acme Systems  ")
                .build());

        assertThat(job.title()).isEqualTo("Senior Backend Engineer");
        assertThat(job.companyName()).isEqualTo("Acme Systems");
    }

    @Test
    @DisplayName("collapses runs of internal whitespace")
    void collapsesInternalWhitespace() {
        assertThat(processor.process(raw().title("Senior    Backend\tEngineer").build()).title())
                .isEqualTo("Senior Backend Engineer");
    }

    @Test
    @DisplayName("leaves the job title otherwise untouched")
    void leavesTitleOtherwiseUntouched() {
        // Seniority, bracketed qualifiers and punctuation all carry meaning.
        String awkward = "Sr. Backend Engineer (Remote, EU) - Payments";
        assertThat(processor.process(raw().title(awkward).build()).title()).isEqualTo(awkward);
    }

    @Test
    @DisplayName("strips HTML from the description but keeps its structure")
    void stripsHtmlFromDescription() {
        TransformedJob job = processor.process(raw()
                .description("<p>We use <b>Java</b>.</p>\n\n\n\n<p>And Spring Boot.</p>")
                .build());

        assertThat(job.description()).doesNotContain("<p>", "<b>");
        assertThat(job.description()).contains("Java", "Spring Boot");
        assertThat(job.description()).doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("normalises employment type vocabulary")
    void normalisesEmploymentType() {
        assertThat(processor.process(raw().employmentType("Full-time").build()).employmentType())
                .isEqualTo("FULL_TIME");
        assertThat(processor.process(raw().employmentType("Permanent").build()).employmentType())
                .isEqualTo("FULL_TIME");
        assertThat(processor.process(raw().employmentType("Contractor").build()).employmentType())
                .isEqualTo("CONTRACT");
    }

    @Test
    @DisplayName("an unrecognised employment type becomes null rather than a rejection")
    void unknownEmploymentTypeBecomesNull() {
        assertThat(processor.process(raw().employmentType("Whatever").build()).employmentType()).isNull();
    }

    @Test
    @DisplayName("a remote location becomes no location at all")
    void remoteBecomesNoLocation() {
        TransformedJob job = processor.process(raw().location("Remote").build());
        assertThat(job.hasLocation()).isFalse();
        assertThat(job.city()).isNull();
        assertThat(job.country()).isNull();
    }

    @Test
    @DisplayName("a two part location has no state")
    void twoPartLocation() {
        TransformedJob job = processor.process(raw().location("Dublin, Ireland").build());
        assertThat(job.city()).isEqualTo("Dublin");
        assertThat(job.state()).isNull();
        assertThat(job.country()).isEqualTo("Ireland");
    }

    @Test
    @DisplayName("a missing title is rejected with a reason")
    void missingTitleIsRejected() {
        assertThatThrownBy(() -> processor.process(raw().title(null).build()))
                .isInstanceOf(RecordRejectedException.class)
                .hasMessageContaining("missing job title");
    }

    @Test
    @DisplayName("an unreadable salary is rejected with a reason")
    void unreadableSalaryIsRejected() {
        assertThatThrownBy(() -> processor.process(raw().salary("150000-120000 USD").build()))
                .isInstanceOf(RecordRejectedException.class)
                .hasMessageContaining("invalid salary");
    }

    @Test
    @DisplayName("an unreadable date is rejected with a reason")
    void unreadableDateIsRejected() {
        assertThatThrownBy(() -> processor.process(raw().postedDate("last Tuesday").build()))
                .isInstanceOf(RecordRejectedException.class)
                .hasMessageContaining("invalid date");
    }

    @Test
    @DisplayName("several problems are all reported together")
    void reportsSeveralProblemsTogether() {
        assertThatThrownBy(() -> processor.process(raw().title(null).description(null).build()))
                .isInstanceOf(RecordRejectedException.class)
                .satisfies(thrown -> assertThat(((RecordRejectedException) thrown).reasons())
                        .contains("missing job title", "missing description"));
    }

    @Test
    @DisplayName("records differing only in case produce the same fingerprint")
    void fingerprintIsCaseInsensitive() {
        String first = processor.process(raw().build()).contentFingerprint();
        String second = processor.process(raw().company("ACME SYSTEMS").build()).contentFingerprint();
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("the same posting from another source produces the same fingerprint")
    void fingerprintIgnoresSource() {
        String first = processor.process(raw().build()).contentFingerprint();
        String mirrored = processor.process(raw()
                .source("mirror")
                .sourceUrl("https://mirror.example.invalid/listing/1")
                .build()).contentFingerprint();
        assertThat(first).isEqualTo(mirrored);
    }

    private static Builder raw() {
        return new Builder();
    }

    private static final class Builder {
        private String title = "Senior Backend Engineer";
        private String company = "Acme Systems";
        private String location = "Austin, Texas, United States";
        private String description = "We build services with Java, Spring Boot and PostgreSQL.";
        private String employmentType = "FULL_TIME";
        private String experience = "5-9 years";
        private String salary = "150000-190000 USD";
        private String postedDate = "2026-08-13";
        private String source = "test";
        private String sourceUrl = "https://acme.example.invalid/jobs/1";

        Builder title(String value) { this.title = value; return this; }
        Builder company(String value) { this.company = value; return this; }
        Builder location(String value) { this.location = value; return this; }
        Builder description(String value) { this.description = value; return this; }
        Builder employmentType(String value) { this.employmentType = value; return this; }
        Builder salary(String value) { this.salary = value; return this; }
        Builder postedDate(String value) { this.postedDate = value; return this; }
        Builder source(String value) { this.source = value; return this; }
        Builder sourceUrl(String value) { this.sourceUrl = value; return this; }

        RawJobRecord build() {
            return new RawJobRecord(title, company, "Software", "https://acme.example.invalid",
                    location, description, employmentType, experience, salary, postedDate, source, sourceUrl);
        }
    }
}
