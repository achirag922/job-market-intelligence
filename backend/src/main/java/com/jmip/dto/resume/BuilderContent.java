package com.jmip.dto.resume;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * V9.4: a resume written in the builder, section by section. Only what the user typed: JMIP never
 * adds, rewrites or suggests content into it. Dates are free text ("Mar 2021", "2021-03").
 *
 * @param template CLASSIC or MODERN; how the preview and the PDF look, not what they say
 */
public record BuilderContent(
        @Pattern(regexp = "CLASSIC|MODERN", message = "must be CLASSIC or MODERN") String template,
        @NotNull(message = "personal information is required") @Valid Personal personal,
        @Size(max = 2000) String summary,
        @Size(max = 60) List<@NotBlank @Size(max = 60) String> skills,
        @Size(max = 30) List<@Valid Experience> experience,
        @Size(max = 20) List<@Valid Education> education,
        @Size(max = 30) List<@Valid Project> projects,
        @Size(max = 30) List<@Valid Certification> certifications,
        @Size(max = 30) List<@NotBlank @Size(max = 300) String> achievements,
        @Size(max = 10) List<@Valid Section> additional) {

    public BuilderContent {
        template = template == null || template.isBlank() ? "CLASSIC" : template;
        summary = blankToNull(summary);
        skills = list(skills);
        experience = list(experience);
        education = list(education);
        projects = list(projects);
        certifications = list(certifications);
        achievements = list(achievements);
        additional = list(additional);
    }

    /** A new resume: the name from the account, everything else empty. */
    public static BuilderContent blank(String fullName) {
        return new BuilderContent("CLASSIC", new Personal(fullName == null || fullName.isBlank() ? "Your Name" : fullName,
                null, null, null, null, null), null, null, null, null, null, null, null, null);
    }

    public record Personal(
            @NotBlank(message = "full name is required") @Size(max = 120) String fullName,
            @Size(max = 160) String headline,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String phone,
            @Size(max = 120) String location,
            @Size(max = 5) List<@NotBlank @Size(max = 200) String> links) {

        public Personal {
            headline = blankToNull(headline);
            email = blankToNull(email);
            phone = blankToNull(phone);
            location = blankToNull(location);
            links = list(links);
        }
    }

    public record Experience(
            @NotBlank(message = "job title is required") @Size(max = 120) String title,
            @NotBlank(message = "company is required") @Size(max = 160) String company,
            @Size(max = 120) String location,
            @Size(max = 20) String start,
            @Size(max = 20) String end,
            boolean current,
            @Size(max = 12) List<@NotBlank @Size(max = 400) String> bullets) {

        public Experience {
            location = blankToNull(location);
            start = blankToNull(start);
            end = blankToNull(end);
            bullets = list(bullets);
        }
    }

    public record Education(
            @NotBlank(message = "degree is required") @Size(max = 160) String degree,
            @NotBlank(message = "institution is required") @Size(max = 160) String institution,
            @Size(max = 120) String location,
            @Size(max = 20) String start,
            @Size(max = 20) String end,
            @Size(max = 600) String details) {

        public Education {
            location = blankToNull(location);
            start = blankToNull(start);
            end = blankToNull(end);
            details = blankToNull(details);
        }
    }

    public record Project(
            @NotBlank(message = "project name is required") @Size(max = 120) String name,
            @Size(max = 200) String url,
            @Size(max = 800) String description,
            @Size(max = 8) List<@NotBlank @Size(max = 400) String> bullets) {

        public Project {
            url = blankToNull(url);
            description = blankToNull(description);
            bullets = list(bullets);
        }
    }

    public record Certification(
            @NotBlank(message = "certification name is required") @Size(max = 160) String name,
            @Size(max = 160) String issuer,
            @Size(max = 20) String date) {

        public Certification {
            issuer = blankToNull(issuer);
            date = blankToNull(date);
        }
    }

    /** An extra section the user names, e.g. Languages or Volunteering. */
    public record Section(
            @NotBlank(message = "section title is required") @Size(max = 60) String title,
            @Size(max = 15) List<@NotBlank @Size(max = 300) String> items) {

        public Section {
            items = list(items);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull)
                .map(value -> value instanceof String text ? (T) text.strip() : value).toList();
    }
}
