package com.jmip.service.resume;

import com.jmip.dto.ExperienceResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeAnalysisServiceTest {

    private static List<SkillResponse> skills(String... names) {
        return IntStream.range(0, names.length).mapToObj(i -> new SkillResponse((long) i + 1, names[i], "OTHER")).toList();
    }

    private static ResumeMatchResponse match(List<SkillResponse> matched, List<SkillResponse> missing, List<SkillResponse> other) {
        int total = matched.size() + missing.size();
        Double percentage = total == 0 ? null : Math.round(matched.size() * 1000.0 / total) / 10.0;
        return new ResumeMatchResponse(UUID.randomUUID(), 1L, "Engineer", "Acme", "Backend", percentage, null,
                total, matched.size() + other.size(), matched.size(), missing.size(), matched, missing, other);
    }

    @Test
    @DisplayName("suggestions name the missing and matching skills, summarising long lists")
    void missingAndMatching() {
        List<String> suggestions = ResumeAnalysisService.suggestions(
                match(skills("Java"), skills("A", "B", "C", "D", "E", "F", "G"), skills("Excel")), null);

        assertThat(suggestions.get(0)).contains("7 skills", "A, B, C, D, E and 2 more");
        assertThat(suggestions.get(1)).contains("(Java)");
        assertThat(suggestions.get(2)).contains("(Excel)");
        assertThat(String.join(" ", suggestions)).doesNotContainIgnoringCase("chance").doesNotContainIgnoringCase("probability");
    }

    @Test
    @DisplayName("a full match, a posting with no skills, and stated experience each get their own advice")
    void edgeCases() {
        assertThat(ResumeAnalysisService.suggestions(match(skills("Java", "Docker"), List.of(), List.of()), null))
                .singleElement().asString().contains("Every skill");
        assertThat(ResumeAnalysisService.suggestions(match(List.of(), List.of(), skills("Java")), null))
                .singleElement().asString().contains("lists no skills");

        List<String> withExperience = ResumeAnalysisService.suggestions(
                match(List.of(), skills("Java"), List.of()), new ExperienceResponse(3, null));
        assertThat(withExperience).anyMatch(text -> text.contains("3+ years"));
    }

    @Test
    @DisplayName("the experience note says what the posting states and that resumes carry no extracted experience")
    void experienceNote() {
        assertThat(ResumeAnalysisService.experienceNote(null)).contains("does not state");
        assertThat(ResumeAnalysisService.experienceNote(new ExperienceResponse(2, 5))).contains("2–5 years", "not read from resumes");
        assertThat(ResumeAnalysisService.experienceNote(new ExperienceResponse(null, 1))).contains("up to 1 year");
        assertThat(ResumeAnalysisService.experienceNote(new ExperienceResponse(4, 4))).contains("4 years");
    }
}
