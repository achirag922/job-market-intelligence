package com.jmip.service.resume;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.ExperienceResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.ResumeComparisonResponse;
import com.jmip.dto.resume.ResumeJobAnalysisResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.entity.Job;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V7.3: job-specific analysis of one resume, and comparison of two resume versions.
 *
 * <p>Both build on what already exists: the analysis on the V3 deterministic match, the
 * comparison on the skills extracted at upload. Neither re-reads a document, calls an AI
 * model, or estimates a chance of being hired. Every resume is reached through
 * {@link ResumeService}, so another account's resume is a 404 here as everywhere.
 */
@Service
public class ResumeAnalysisService {

    static final String DISCLAIMER = "This compares the skills named in your resume with the skills named in the posting. "
            + "It is not a prediction of whether you will be invited to interview or hired.";

    /** How many skill names a suggestion spells out before summarising the rest. */
    private static final int NAMED_IN_SUGGESTION = 5;

    private final ResumeMatchService resumeMatchService;
    private final ResumeService resumeService;
    private final JobMapper jobMapper;

    public ResumeAnalysisService(ResumeMatchService resumeMatchService, ResumeService resumeService, JobMapper jobMapper) {
        this.resumeMatchService = resumeMatchService;
        this.resumeService = resumeService;
        this.jobMapper = jobMapper;
    }

    @Transactional(readOnly = true)
    public ResumeJobAnalysisResponse analyzeJob(UUID resumeId, Long jobId) {
        ResumeMatchService.MatchContext context = resumeMatchService.matchWithContext(resumeId, jobId);
        Resume resume = context.resume();
        Job job = context.job();
        ResumeMatchResponse match = context.match();
        ExperienceResponse required = ExperienceResponse.of(job.getExperienceMin(), job.getExperienceMax());

        return new ResumeJobAnalysisResponse(
                resume.getId(),
                resume.getTitle(),
                resume.getVersionLabel(),
                match.jobId(),
                match.jobTitle(),
                match.companyName(),
                match.jobCategory(),
                match.matchPercentage(),
                match.matchNote(),
                match.totalJobSkills(),
                match.matchedSkillCount(),
                match.missingSkillCount(),
                match.matchedSkills(),
                match.missingSkills(),
                match.resumeOnlySkills(),
                new ResumeJobAnalysisResponse.Experience(required, experienceNote(required)),
                suggestions(match, required),
                DISCLAIMER);
    }

    @Transactional(readOnly = true)
    public ResumeComparisonResponse compare(UUID firstId, UUID secondId) {
        if (firstId.equals(secondId)) {
            throw new InvalidRequestException("Choose two different resumes to compare");
        }
        Resume first = resumeService.requireCompletedResume(firstId);
        Resume second = resumeService.requireCompletedResume(secondId);

        Set<Long> firstIds = idsOf(first.getSkills());
        Set<Long> secondIds = idsOf(second.getSkills());
        ResumeComparisonResponse.Version a = version(first);
        ResumeComparisonResponse.Version b = version(second);

        return new ResumeComparisonResponse(
                a,
                b,
                sorted(second.getSkills(), skill -> !firstIds.contains(skill.getId())),
                sorted(first.getSkills(), skill -> !secondIds.contains(skill.getId())),
                sorted(first.getSkills(), skill -> secondIds.contains(skill.getId())),
                differentFields(a, b));
    }

    // ------------------------------------------------------------------ suggestions

    /** Only what the data shows: which skills matched, which are missing, what experience is stated. */
    static List<String> suggestions(ResumeMatchResponse match, ExperienceResponse required) {
        List<String> suggestions = new ArrayList<>();
        if (match.totalJobSkills() == 0) {
            suggestions.add("This posting lists no skills to compare against. Read its description to decide what to emphasise.");
        } else if (match.missingSkillCount() == 0) {
            suggestions.add("Every skill this posting lists appears in your resume. Show depth for them: where you used each, "
                    + "for how long, and what came of it.");
        } else {
            suggestions.add("The posting lists " + count(match.missingSkillCount(), "skill") + " your resume does not mention: "
                    + names(match.missingSkills()) + ". If you have used any of them, name them explicitly, with where and how.");
        }
        if (match.matchedSkillCount() > 0 && match.missingSkillCount() > 0) {
            suggestions.add("Put the skills that match (" + names(match.matchedSkills()) + ") where a reader sees them first, "
                    + "such as a summary or a skills section near the top.");
        }
        if (required != null) {
            suggestions.add("The posting asks for " + describe(required) + " of experience. "
                    + "Make the dates and length of your relevant roles easy to find.");
        }
        if (!match.resumeOnlySkills().isEmpty() && match.matchedSkillCount() > 0) {
            suggestions.add("Your resume also lists skills this posting does not ask for (" + names(match.resumeOnlySkills())
                    + "). For this application, consider giving them less space than the matching ones.");
        }
        return suggestions;
    }

    static String experienceNote(ExperienceResponse required) {
        return required == null
                ? "The posting does not state an experience requirement."
                : "The posting asks for " + describe(required) + ". Experience is not read from resumes, "
                        + "so check that yours states it clearly.";
    }

    private static String describe(ExperienceResponse range) {
        if (range.min() != null && range.max() != null) {
            return range.min().equals(range.max()) ? count(range.min(), "year") : range.min() + "–" + range.max() + " years";
        }
        return range.min() != null ? range.min() + "+ years" : "up to " + count(range.max(), "year");
    }

    private static String names(List<SkillResponse> skills) {
        String named = skills.stream().limit(NAMED_IN_SUGGESTION).map(SkillResponse::name).collect(Collectors.joining(", "));
        int rest = skills.size() - NAMED_IN_SUGGESTION;
        return rest > 0 ? named + " and " + rest + " more" : named;
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    // ------------------------------------------------------------------ comparison

    private ResumeComparisonResponse.Version version(Resume resume) {
        return new ResumeComparisonResponse.Version(resume.getId(), resume.getTitle(), resume.getVersionLabel(),
                resume.getOriginalFileName(), resume.isDefaultResume(), resume.getSkills().size(),
                resume.getUploadedAt(), resume.getUpdatedAt());
    }

    private static List<String> differentFields(ResumeComparisonResponse.Version a, ResumeComparisonResponse.Version b) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(a.title(), b.title())) fields.add("title");
        if (!Objects.equals(a.versionLabel(), b.versionLabel())) fields.add("versionLabel");
        if (!Objects.equals(a.fileName(), b.fileName())) fields.add("fileName");
        if (a.isDefault() != b.isDefault()) fields.add("isDefault");
        if (a.skillCount() != b.skillCount()) fields.add("skillCount");
        if (!Objects.equals(a.uploadedAt(), b.uploadedAt())) fields.add("uploadedAt");
        return fields;
    }

    private List<SkillResponse> sorted(Set<Skill> skills, java.util.function.Predicate<Skill> keep) {
        return skills.stream().filter(keep).map(jobMapper::toSkill)
                .sorted(Comparator.comparing(SkillResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static Set<Long> idsOf(Set<Skill> skills) {
        return skills.stream().map(Skill::getId).collect(Collectors.toSet());
    }
}
