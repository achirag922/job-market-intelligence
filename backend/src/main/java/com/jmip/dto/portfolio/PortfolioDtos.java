package com.jmip.dto.portfolio;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.resume.BuilderContent.Certification;
import com.jmip.dto.resume.BuilderContent.Education;
import com.jmip.dto.resume.BuilderContent.Experience;
import com.jmip.dto.resume.BuilderContent.Project;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * V9.7: the professional portfolio. Experience, education, projects and certifications are the
 * V9.4 resume builder's own records. The owner always comes from the session; the public view
 * carries no email, phone, account id or anything outside the visible sections.
 */
public final class PortfolioDtos {

    private PortfolioDtos() {
    }

    public static final String SLUG_PATTERN = "^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$";
    public static final String SLUG_MESSAGE = "use 3 to 50 lowercase letters, digits and hyphens, starting and ending "
            + "with a letter or digit";
    private static final String URL_PATTERN = "(?i)^https?://\\S+$";

    public record Content(
            @Size(max = 160) String headline,
            @Size(max = 3000) String about,
            @Size(max = 60) List<@NotBlank @Size(max = 60) String> skills,
            @Size(max = 30) List<@Valid Experience> experience,
            @Size(max = 20) List<@Valid Education> education,
            @Size(max = 30) List<@Valid Project> projects,
            @Size(max = 30) List<@Valid Certification> certifications,
            @Size(max = 30) List<@NotBlank @Size(max = 300) String> achievements,
            @Size(max = 10) List<@Valid Link> links) {

        public Content {
            headline = blankToNull(headline);
            about = blankToNull(about);
            skills = list(skills);
            experience = list(experience);
            education = list(education);
            projects = list(projects);
            certifications = list(certifications);
            achievements = list(achievements);
            links = list(links);
        }

        public static Content empty() {
            return new Content(null, null, null, null, null, null, null, null, null);
        }
    }

    public record Link(
            @NotBlank(message = "a link needs a label") @Size(max = 60) String label,
            @NotBlank(message = "a link needs an address") @Size(max = 300)
            @Pattern(regexp = URL_PATTERN, message = "must be an http or https address") String url) {
    }

    /** Which sections a published profile shows. The name and headline always show; career goals are off unless chosen. */
    public record Sections(Boolean about, Boolean skills, Boolean experience, Boolean education, Boolean projects,
                           Boolean certifications, Boolean achievements, Boolean careerGoals, Boolean links) {

        public Sections {
            about = orTrue(about);
            skills = orTrue(skills);
            experience = orTrue(experience);
            education = orTrue(education);
            projects = orTrue(projects);
            certifications = orTrue(certifications);
            achievements = orTrue(achievements);
            careerGoals = careerGoals != null && careerGoals;
            links = orTrue(links);
        }

        public static Sections defaults() {
            return new Sections(null, null, null, null, null, null, null, null, null);
        }

        private static Boolean orTrue(Boolean value) {
            return value == null || value;
        }
    }

    /** @param slug optional on create: absent means one made from the display name */
    public record SaveRequest(
            @NotBlank(message = "a display name is required") @Size(max = 120) String displayName,
            @Pattern(regexp = SLUG_PATTERN, message = SLUG_MESSAGE) String slug,
            @NotNull(message = "profile content is required") @Valid Content content,
            Sections sections) {
    }

    public record SlugRequest(@NotBlank @Pattern(regexp = SLUG_PATTERN, message = SLUG_MESSAGE) String slug) {
    }

    /** The owner's view. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Portfolio(String slug, String displayName, String visibility, String publicPath, Content content,
                            Sections sections, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                            OffsetDateTime publishedAt) {
    }

    /**
     * What anyone can see at /profile/{slug}, and what the owner's preview shows. Hidden sections
     * are absent. No ids, no email, no phone.
     *
     * @param careerGoals the target roles of the owner's active career goals, when that section is shown
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicProfile(String displayName, String headline, String about, List<String> skills,
                                List<Experience> experience, List<Education> education, List<Project> projects,
                                List<Certification> certifications, List<String> achievements, List<String> careerGoals,
                                List<Link> links, OffsetDateTime updatedAt) {
    }

    /**
     * A draft built from one of the owner's resumes, for the editor to review; nothing is saved.
     *
     * @param learnedSkills skills completed in the V9.5 learning plan and not in the draft, offered separately
     */
    public record ImportDraft(String displayName, Content content, List<String> learnedSkills, String source) {
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
    }
}
