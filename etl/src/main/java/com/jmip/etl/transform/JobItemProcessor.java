package com.jmip.etl.transform;

import com.jmip.etl.model.JobClassification;
import com.jmip.etl.model.TransformedJob;
import com.jmip.etl.raw.RawJobRecord;
import com.jmip.etl.transform.ExperienceParser.ExperienceRange;
import com.jmip.etl.transform.LocationParser.ParsedLocation;
import com.jmip.etl.transform.SalaryParser.SalaryRange;
import com.jmip.etl.validation.JobValidator;
import com.jmip.etl.validation.RecordRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns one raw record into something loadable: clean, parsed, enriched with skills and
 * validated.
 *
 * <p>A field that is present but unreadable produces a rejection reason rather than a
 * thrown parser exception, so a record with two problems reports both instead of only
 * the first one encountered.
 */
@Component
public class JobItemProcessor implements ItemProcessor<RawJobRecord, TransformedJob> {

    private static final Logger log = LoggerFactory.getLogger(JobItemProcessor.class);

    /** The formats datasets publish in practice, tried in order. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    private final TextNormalizer textNormalizer;
    private final LocationParser locationParser;
    private final ExperienceParser experienceParser;
    private final SalaryParser salaryParser;
    private final EmploymentTypeNormalizer employmentTypeNormalizer;
    private final SkillExtractor skillExtractor;
    private final JobDescriptionProcessor jobDescriptionProcessor;
    private final JobClassifier jobClassifier;
    private final ContentFingerprint contentFingerprint;
    private final JobValidator jobValidator;

    public JobItemProcessor(TextNormalizer textNormalizer,
                            LocationParser locationParser,
                            ExperienceParser experienceParser,
                            SalaryParser salaryParser,
                            EmploymentTypeNormalizer employmentTypeNormalizer,
                            SkillExtractor skillExtractor,
                            JobDescriptionProcessor jobDescriptionProcessor,
                            JobClassifier jobClassifier,
                            ContentFingerprint contentFingerprint,
                            JobValidator jobValidator) {
        this.textNormalizer = textNormalizer;
        this.locationParser = locationParser;
        this.experienceParser = experienceParser;
        this.salaryParser = salaryParser;
        this.employmentTypeNormalizer = employmentTypeNormalizer;
        this.skillExtractor = skillExtractor;
        this.jobDescriptionProcessor = jobDescriptionProcessor;
        this.jobClassifier = jobClassifier;
        this.contentFingerprint = contentFingerprint;
        this.jobValidator = jobValidator;
    }

    @Override
    public TransformedJob process(RawJobRecord raw) {
        List<String> parseFailures = new ArrayList<>();

        String title = textNormalizer.normalizeTitle(raw.title());
        String company = textNormalizer.normalizeCompany(raw.company());
        String description = textNormalizer.normalizeDescription(raw.description());

        // The stored description keeps its own formatting; this working copy is the one
        // extraction and classification read, with markup and layout artefacts removed.
        String processedDescription = jobDescriptionProcessor.process(description);

        ParsedLocation location = parseLocation(raw, parseFailures);
        ExperienceRange experience = parseExperience(raw, parseFailures);
        SalaryRange salary = parseSalary(raw, parseFailures);
        LocalDate postedDate = parsePostedDate(raw, parseFailures);

        Set<String> skills = skillExtractor.extract(title, processedDescription);

        TransformedJob job = new TransformedJob(
                title,
                company,
                textNormalizer.normalize(raw.companyIndustry()),
                textNormalizer.normalize(raw.companyWebsite()),
                textNormalizer.normalize(location.city()),
                textNormalizer.normalize(location.state()),
                textNormalizer.normalize(location.country()),
                description,
                employmentTypeNormalizer.normalize(raw.employmentType()),
                experience.min(),
                experience.max(),
                salary.min(),
                salary.max(),
                salary.currency(),
                postedDate,
                textNormalizer.normalize(raw.source()),
                textNormalizer.normalize(raw.sourceUrl()),
                null,
                skills,
                null);

        List<String> reasons = new ArrayList<>(parseFailures);
        reasons.addAll(jobValidator.validate(job));
        if (!reasons.isEmpty()) {
            log.debug("Rejecting record '{}': {}", raw.title(), reasons);
            throw new RecordRejectedException(reasons);
        }

        // Computed last, so the fingerprint always reflects the normalised values that
        // are actually written.
        String fingerprint = contentFingerprint.compute(
                job.title(), job.companyName(), job.city(), job.state(), job.country(), job.postedDate());

        // Classified only once the record is known to be loadable, so effort is not spent
        // on postings that are about to be rejected.
        JobClassification classification = jobClassifier.classify(title, processedDescription, skills);

        return new TransformedJob(
                job.title(), job.companyName(), job.companyIndustry(), job.companyWebsite(),
                job.city(), job.state(), job.country(), job.description(), job.employmentType(),
                job.experienceMin(), job.experienceMax(), job.salaryMin(), job.salaryMax(),
                job.currency(), job.postedDate(), job.source(), job.sourceUrl(),
                fingerprint, job.skills(), classification);
    }

    private ParsedLocation parseLocation(RawJobRecord raw, List<String> failures) {
        try {
            return locationParser.parse(raw.location());
        } catch (ValueParseException e) {
            failures.add("invalid location: " + e.getMessage());
            return ParsedLocation.NONE;
        }
    }

    private ExperienceRange parseExperience(RawJobRecord raw, List<String> failures) {
        try {
            return experienceParser.parse(raw.experience());
        } catch (ValueParseException e) {
            failures.add("invalid experience: " + e.getMessage());
            return ExperienceRange.NONE;
        }
    }

    private SalaryRange parseSalary(RawJobRecord raw, List<String> failures) {
        try {
            return salaryParser.parse(raw.salary());
        } catch (ValueParseException e) {
            failures.add("invalid salary: " + e.getMessage());
            return SalaryRange.NONE;
        }
    }

    private LocalDate parsePostedDate(RawJobRecord raw, List<String> failures) {
        String value = textNormalizer.normalize(raw.postedDate());
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
        }
        failures.add("invalid date: unrecognised posted date '" + value + "'");
        return null;
    }
}
