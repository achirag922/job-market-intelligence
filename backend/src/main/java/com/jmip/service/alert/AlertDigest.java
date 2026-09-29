package com.jmip.service.alert;

import com.jmip.entity.AlertFrequency;

import java.util.List;
import java.util.Locale;

/**
 * V8.4: one digest email. Plain text; it carries job facts and links only, never an account
 * id, a token or anything about the recipient beyond their name.
 */
public record AlertDigest(String subject, String body) {

    /** One job in the digest. {@code matchPercentage} is null when the user has no processed resume. */
    public record Item(long jobId, String title, String company, String location, String workMode, Double matchPercentage) {
    }

    public static AlertDigest compose(String fullName, String alertName, AlertFrequency frequency, List<Item> items,
                                      String appUrl) {
        String base = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        String period = frequency == AlertFrequency.DAILY ? "daily" : "weekly";
        String subject = "JMIP %s digest: %d new %s for \"%s\"".formatted(period, items.size(),
                items.size() == 1 ? "job" : "jobs", alertName);

        StringBuilder body = new StringBuilder();
        body.append("Hello").append(fullName == null || fullName.isBlank() ? "" : " " + fullName).append(",\n\n");
        body.append("New postings matching your alert \"").append(alertName).append("\":\n\n");
        for (Item item : items) {
            body.append("• ").append(item.title()).append(" — ").append(item.company()).append('\n');
            body.append("  ").append(item.location() == null ? "Location not stated" : item.location());
            if (item.workMode() != null) {
                body.append(" · ").append(item.workMode());
            }
            body.append('\n');
            if (item.matchPercentage() != null) {
                body.append("  Match: ").append(String.format(Locale.ROOT, "%.0f", item.matchPercentage()))
                        .append("% with your resume and preferences\n");
            }
            body.append("  ").append(base).append("/jobs/").append(item.jobId()).append("\n\n");
        }
        body.append("Match scores compare skills, experience, location, work mode and salary; they are not a hiring prediction.\n");
        body.append("Pause this alert or change how often you hear about it: ").append(base).append("/alerts\n");
        return new AlertDigest(subject, body.toString());
    }
}
