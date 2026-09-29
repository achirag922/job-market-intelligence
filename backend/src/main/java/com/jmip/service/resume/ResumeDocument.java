package com.jmip.service.resume;

import com.jmip.dto.resume.BuilderContent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * V9.4: a built resume as plain text, with each section under its own heading line. This is what
 * analysis, skill extraction, V8.6 optimisation and matching read, exactly as they read an uploaded
 * resume's extracted text. Only the user's own words appear; nothing is added.
 */
final class ResumeDocument {

    private ResumeDocument() {
    }

    static String plainText(BuilderContent content) {
        List<String> lines = new ArrayList<>();
        BuilderContent.Personal personal = content.personal();
        lines.add(personal.fullName());
        add(lines, personal.headline());
        add(lines, join(" | ", personal.email(), personal.phone(), personal.location()));
        personal.links().forEach(lines::add);
        if (content.summary() != null) {
            section(lines, "Summary");
            lines.add(content.summary());
        }
        if (!content.skills().isEmpty()) {
            section(lines, "Skills");
            lines.add(String.join(", ", content.skills()));
        }
        if (!content.experience().isEmpty()) {
            section(lines, "Experience");
            for (BuilderContent.Experience job : content.experience()) {
                lines.add(job.title() + ", " + job.company() + (job.location() == null ? "" : ", " + job.location()));
                add(lines, dates(job.start(), job.end(), job.current()));
                job.bullets().forEach(bullet -> lines.add("- " + bullet));
            }
        }
        if (!content.education().isEmpty()) {
            section(lines, "Education");
            for (BuilderContent.Education school : content.education()) {
                lines.add(school.degree() + ", " + school.institution() + (school.location() == null ? "" : ", " + school.location()));
                add(lines, dates(school.start(), school.end(), false));
                add(lines, school.details());
            }
        }
        if (!content.projects().isEmpty()) {
            section(lines, "Projects");
            for (BuilderContent.Project project : content.projects()) {
                lines.add(project.name() + (project.url() == null ? "" : " (" + project.url() + ")"));
                add(lines, project.description());
                project.bullets().forEach(bullet -> lines.add("- " + bullet));
            }
        }
        if (!content.certifications().isEmpty()) {
            section(lines, "Certifications");
            content.certifications().forEach(cert -> lines.add(join(", ", cert.name(), cert.issuer(), cert.date())));
        }
        if (!content.achievements().isEmpty()) {
            section(lines, "Achievements");
            content.achievements().forEach(item -> lines.add("- " + item));
        }
        for (BuilderContent.Section extra : content.additional()) {
            section(lines, extra.title());
            extra.items().forEach(item -> lines.add("- " + item));
        }
        return String.join("\n", lines);
    }

    /** "Mar 2021 – Present", "2019 – 2021", or null when neither end is given. */
    static String dates(String start, String end, boolean current) {
        String until = current ? "Present" : end;
        if (start == null && until == null) {
            return null;
        }
        return start == null ? until : until == null ? start : start + " – " + until;
    }

    static String join(String separator, String... parts) {
        String joined = java.util.Arrays.stream(parts).filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(separator));
        return joined.isEmpty() ? null : joined;
    }

    private static void section(List<String> lines, String heading) {
        lines.add("");
        lines.add(heading);
    }

    private static void add(List<String> lines, String line) {
        if (line != null && !line.isBlank()) {
            lines.add(line);
        }
    }
}
