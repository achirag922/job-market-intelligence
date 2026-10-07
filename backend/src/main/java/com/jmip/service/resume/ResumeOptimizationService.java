package com.jmip.service.resume;

import com.jmip.dto.ExperienceResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.MatchBreakdown;
import com.jmip.dto.resume.ResumeComparisonResponse;
import com.jmip.dto.resume.ResumeJobComparisonResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.resume.ResumeOptimizationResponse;
import com.jmip.dto.resume.ResumeOptimizationResponse.Keyword;
import com.jmip.dto.resume.ResumeOptimizationResponse.Suggestion;
import com.jmip.entity.Job;
import com.jmip.entity.Resume;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V8.6: job-specific resume optimisation. The match is the V8.3 one from
 * {@link ResumeMatchService}, the skill suggestions are the V7.3 ones from
 * {@link ResumeAnalysisService}, and keywords and sections come from
 * {@link ResumeKeywordAnalyzer}. Ownership is checked by {@link ResumeService} on every resume:
 * another account's resume answers 404. Nothing here writes to the resume.
 */
@Service
public class ResumeOptimizationService {

    private static final String SECTIONS_TEXT = "SECTIONS";

    static final String DISCLAIMER = "Suggestions come only from this resume and this posting. JMIP does not rewrite your "
            + "resume or add anything to it: use a skill or term only if it truthfully describes your experience.";

    /** How many missing terms one suggestion names. */
    private static final int TERMS_IN_SUGGESTION = 5;

    private final ResumeMatchService matchService;
    private final ResumeAnalysisService analysisService;
    private final ResumeKeywordAnalyzer keywords;

    public ResumeOptimizationService(ResumeMatchService matchService, ResumeAnalysisService analysisService,
                                     ResumeKeywordAnalyzer keywords) {
        this.matchService = matchService;
        this.analysisService = analysisService;
        this.keywords = keywords;
    }

    @Transactional(readOnly = true)
    public ResumeOptimizationResponse optimize(UUID resumeId, Long jobId) {
        ResumeMatchService.MatchContext context = matchService.matchWithContext(resumeId, jobId);
        Resume resume = context.resume();
        Job job = context.job();
        ResumeMatchResponse match = context.match();
        ExperienceResponse required = ExperienceResponse.of(job.getExperienceMin(), job.getExperienceMax());
        MatchBreakdown breakdown = match.breakdown();

        String text = resume.getExtractedText();
        boolean hasText = text != null && !text.isBlank();
        ResumeKeywordAnalyzer.Result terms = hasText
                ? keywords.analyze(job.getTitle(), job.getDescription(), text, skillNames(match)) : null;
        List<String> found = hasText ? keywords.sections(text) : List.of();
        List<String> missingSections = hasText
                ? ResumeKeywordAnalyzer.SECTIONS.keySet().stream().filter(name -> !found.contains(name)).toList() : List.of();

        String experienceGap = breakdown != null && breakdown.experience().available()
                ? breakdown.experience().detail() : ResumeAnalysisService.experienceNote(required);

        return new ResumeOptimizationResponse(resume.getId(), resume.getTitle(), resume.getVersionLabel(), job.getId(),
                job.getTitle(), match.companyName(), breakdown == null ? null : breakdown.overallPercentage(),
                match.matchPercentage(), breakdown, match.matchedSkills(), match.missingSkills(), match.resumeOnlySkills(),
                required, experienceGap,
                terms == null ? null : keywordsOf(terms.present()),
                terms == null ? null : keywordsOf(terms.missing()),
                terms == null ? null : keywordsOf(terms.overused()),
                hasText ? null : "This resume's text is not stored (it may have been removed under the retention policy), "
                        + "so keywords and sections cannot be checked.",
                hasText ? found : null, hasText ? missingSections : null,
                suggestions(match, required, terms, found, hasText, job.getTitle()), DISCLAIMER);
    }

    /** Two versions against one job: what changed in skills, match and the posting's terms. */
    @Transactional(readOnly = true)
    public ResumeJobComparisonResponse compareForJob(UUID firstId, UUID secondId, Long jobId) {
        ResumeComparisonResponse versions = analysisService.compare(firstId, secondId);
        ResumeMatchService.MatchContext first = matchService.matchWithContext(firstId, jobId);
        ResumeMatchService.MatchContext second = matchService.matchWithContext(secondId, jobId);
        ResumeJobComparisonResponse.Score a = score(first.match());
        ResumeJobComparisonResponse.Score b = score(second.match());

        List<String> gained = null;
        List<String> lost = null;
        String textA = first.resume().getExtractedText();
        String textB = second.resume().getExtractedText();
        if (textA != null && !textA.isBlank() && textB != null && !textB.isBlank()) {
            Set<String> names = new HashSet<>(skillNames(first.match()));
            names.addAll(skillNames(second.match()));
            Job job = first.job();
            Set<String> inA = presentTerms(keywords.analyze(job.getTitle(), job.getDescription(), textA, names));
            Set<String> inB = presentTerms(keywords.analyze(job.getTitle(), job.getDescription(), textB, names));
            gained = inB.stream().filter(term -> !inA.contains(term)).sorted().toList();
            lost = inA.stream().filter(term -> !inB.contains(term)).sorted().toList();
        }
        return new ResumeJobComparisonResponse(versions, jobId, first.job().getTitle(), a, b,
                change(a.overallMatchPercentage(), b.overallMatchPercentage()),
                change(a.skillMatchPercentage(), b.skillMatchPercentage()), gained, lost);
    }

    // ------------------------------------------------------------------ suggestions

    static List<Suggestion> suggestions(ResumeMatchResponse match, ExperienceResponse required,
                                        ResumeKeywordAnalyzer.Result terms, List<String> sections, boolean hasText,
                                        String jobTitle) {
        List<Suggestion> suggestions = new ArrayList<>();
        // The V7.3 skill and experience suggestions, grouped.
        for (String text : ResumeAnalysisService.suggestions(match, required)) {
            String area = text.contains("of experience") ? "EXPERIENCE"
                    : text.startsWith("Put the skills") || text.contains("less space") ? "ALIGNMENT" : "SKILLS";
            suggestions.add(new Suggestion(area, text));
        }
        if (terms != null && !terms.missing().isEmpty()) {
            String listed = terms.missing().stream().limit(TERMS_IN_SUGGESTION)
                    .map(term -> "\"" + term.term() + "\" (" + term.jobMentions() + "×)").collect(Collectors.joining(", "));
            suggestions.add(new Suggestion("KEYWORDS", "The posting uses " + listed + " and your resume does not. "
                    + "Where a term describes work you have actually done, use the posting's wording when you describe it; "
                    + "leave out any that do not apply to you."));
        }
        if (terms != null) {
            for (ResumeKeywordAnalyzer.Term term : terms.overused()) {
                suggestions.add(new Suggestion("KEYWORDS", "Your resume repeats \"" + term.term() + "\" " + term.resumeMentions()
                        + " times. Repetition does not make a resume stronger; keep the mentions that describe real work."));
            }
        }
        if (hasText) {
            if (!sections.contains("Skills") && match.totalJobSkills() > 0) {
                suggestions.add(new Suggestion(SECTIONS_TEXT, "No Skills heading was recognised. A short, clearly headed skills "
                        + "list makes the skills you have easy to find."));
            }
            if (!sections.contains("Summary")) {
                suggestions.add(new Suggestion(SECTIONS_TEXT, "No summary heading was recognised. A two or three line summary "
                        + "that names the kind of role you do, if \"" + jobTitle + "\" fits it, aligns the resume with this posting."));
            }
            if (!sections.contains("Experience")) {
                suggestions.add(new Suggestion(SECTIONS_TEXT, "No Experience heading was recognised. Headed roles with dates "
                        + "let a reader check the experience the posting asks for."));
            }
        }
        return suggestions;
    }

    private static List<Keyword> keywordsOf(List<ResumeKeywordAnalyzer.Term> terms) {
        return terms.stream().map(term -> new Keyword(term.term(), term.jobMentions(), term.resumeMentions())).toList();
    }

    private static Set<String> presentTerms(ResumeKeywordAnalyzer.Result result) {
        return result.present().stream().map(ResumeKeywordAnalyzer.Term::term).collect(Collectors.toSet());
    }

    private static List<String> skillNames(ResumeMatchResponse match) {
        return Stream.of(match.matchedSkills(), match.missingSkills(), match.resumeOnlySkills())
                .flatMap(List::stream).map(SkillResponse::name).toList();
    }

    private static ResumeJobComparisonResponse.Score score(ResumeMatchResponse match) {
        return new ResumeJobComparisonResponse.Score(match.breakdown() == null ? null : match.breakdown().overallPercentage(),
                match.matchPercentage(), match.matchedSkillCount(), match.missingSkillCount());
    }

    private static Double change(Double before, Double after) {
        return before == null || after == null ? null : Math.round((after - before) * 10.0) / 10.0;
    }
}
