package com.jmip.service.resume;

import com.jmip.dto.resume.MatchBreakdown;
import com.jmip.dto.resume.MatchBreakdown.Dimension;
import com.jmip.dto.resume.MatchBreakdown.Status;
import com.jmip.dto.resume.MatchPreferences;
import com.jmip.entity.Job;
import com.jmip.entity.Location;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * V8.3: the match score beyond skills. Skills stay the V3 share of the job's listed skills;
 * experience, location, work mode and salary are added from the posting and the user's own
 * {@link MatchPreferences}, each only when both sides give the data.
 *
 * <p>Fixed weights (skills 60, experience 15, location 10, work mode 10, salary 5), and the
 * overall score is the weighted average of the dimensions that are available. With no
 * preferences set, the overall score is therefore exactly the V3 skill match.
 */
@Component
public class JobMatchScorer {

    static final int SKILLS_WEIGHT = 60;
    static final int EXPERIENCE_WEIGHT = 15;
    static final int LOCATION_WEIGHT = 10;
    static final int WORK_MODE_WEIGHT = 10;
    static final int SALARY_WEIGHT = 5;

    /** Points lost per year short of a posting's minimum experience. */
    private static final double PER_YEAR_SHORT = 25;
    private static final double ABOVE_RANGE = 75;

    // The V7.5 market work-mode rule (MarketIntelligenceRepository.WORK_MODE), in the same order.
    private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOTE = Pattern.compile("\\bremote\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_SITE = Pattern.compile("\\b(on-?site|in[- ]office)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @param matchedSkills how many of the job's skills the resume shows
     * @param jobSkills     how many skills the job lists
     */
    public MatchBreakdown score(int matchedSkills, int jobSkills, Job job, MatchPreferences preferences) {
        Dimension skills = jobSkills == 0
                ? Dimension.unavailable(SKILLS_WEIGHT, "The posting lists no skills")
                : new Dimension(matchedSkills == jobSkills ? Status.MATCH : matchedSkills == 0 ? Status.NO_MATCH : Status.PARTIAL,
                round(matchedSkills * 100.0 / jobSkills), SKILLS_WEIGHT,
                matchedSkills + " of " + jobSkills + " required skills on your resume");
        Dimension experience = experience(job, preferences.yearsExperience());
        Dimension location = location(job.getLocation(), preferences.preferredLocation());
        Dimension workMode = workMode(job.getDescription(), preferences.workMode());
        Dimension salary = salary(job, preferences.minSalary(), preferences.salaryCurrency());

        Double overall = null;
        if (skills.available()) {
            double points = 0;
            int weights = 0;
            for (Dimension dimension : List.of(skills, experience, location, workMode, salary)) {
                if (dimension.available()) {
                    points += dimension.score() * dimension.weight();
                    weights += dimension.weight();
                }
            }
            overall = round(points / weights);
        }
        return new MatchBreakdown(overall, skills, experience, location, workMode, salary);
    }

    Dimension experience(Job job, Integer years) {
        Short min = job.getExperienceMin();
        Short max = job.getExperienceMax();
        if (years == null) {
            return Dimension.unavailable(EXPERIENCE_WEIGHT, "Add your years of experience in match preferences");
        }
        if (min == null && max == null) {
            return Dimension.unavailable(EXPERIENCE_WEIGHT, "The posting does not state the experience it needs");
        }
        String range = min == null ? "up to " + max : max == null ? min + "+" : min + "–" + max;
        if (min != null && years < min) {
            int gap = min - years;
            double score = Math.max(0, 100 - PER_YEAR_SHORT * gap);
            return new Dimension(score > 0 ? Status.PARTIAL : Status.NO_MATCH, score, EXPERIENCE_WEIGHT,
                    years + " years is " + gap + (gap == 1 ? " year" : " years") + " short of the " + range + " years asked");
        }
        if (max != null && years > max) {
            return new Dimension(Status.PARTIAL, ABOVE_RANGE, EXPERIENCE_WEIGHT,
                    years + " years is above the " + range + " years asked");
        }
        return new Dimension(Status.MATCH, 100.0, EXPERIENCE_WEIGHT, years + " years fits the " + range + " years asked");
    }

    /**
     * "City" or "City, …" must match the job's city; a single name that is not the city may
     * match its state or country, meaning anywhere there. Same country, other city: half.
     */
    Dimension location(Location location, String preferred) {
        if (preferred == null) {
            return Dimension.unavailable(LOCATION_WEIGHT, "Add a preferred location in match preferences");
        }
        if (location == null) {
            return Dimension.unavailable(LOCATION_WEIGHT, "The posting gives no location");
        }
        List<String> parts = Arrays.stream(preferred.split(",")).map(JobMatchScorer::norm).filter(p -> !p.isEmpty()).toList();
        String where = describe(location);
        if (parts.isEmpty()) {
            return Dimension.unavailable(LOCATION_WEIGHT, "Add a preferred location in match preferences");
        }
        String city = norm(location.getCity());
        String state = norm(location.getState());
        String country = norm(location.getCountry());
        if (!city.isEmpty() && parts.get(0).equals(city)) {
            return new Dimension(Status.MATCH, 100.0, LOCATION_WEIGHT, where + " is your preferred city");
        }
        if (parts.size() == 1 && (parts.get(0).equals(state) || parts.get(0).equals(country))) {
            return new Dimension(Status.MATCH, 100.0, LOCATION_WEIGHT, where + " is in " + preferred);
        }
        if (parts.stream().skip(1).anyMatch(p -> p.equals(country) || p.equals(state))) {
            return new Dimension(Status.PARTIAL, 50.0, LOCATION_WEIGHT, where + " is in your preferred region, another city");
        }
        return new Dimension(Status.NO_MATCH, 0.0, LOCATION_WEIGHT, where + " is not " + preferred);
    }

    Dimension workMode(String description, String preferred) {
        if (preferred == null) {
            return Dimension.unavailable(WORK_MODE_WEIGHT, "Add a work-mode preference in match preferences");
        }
        String mode = workModeOf(description);
        if (mode == null) {
            return Dimension.unavailable(WORK_MODE_WEIGHT, "The posting does not say whether it is remote, hybrid or on-site");
        }
        String label = label(mode);
        if (mode.equals(preferred)) {
            return new Dimension(Status.MATCH, 100.0, WORK_MODE_WEIGHT, label + ", as you prefer");
        }
        if (mode.equals("HYBRID") || preferred.equals("HYBRID")) {
            return new Dimension(Status.PARTIAL, 50.0, WORK_MODE_WEIGHT, label + "; you prefer " + label(preferred).toLowerCase(Locale.ROOT));
        }
        return new Dimension(Status.NO_MATCH, 0.0, WORK_MODE_WEIGHT, label + "; you prefer " + label(preferred).toLowerCase(Locale.ROOT));
    }

    /** Only in the same currency, and only against what the posting states; nothing is converted. */
    Dimension salary(Job job, BigDecimal minimum, String currency) {
        if (minimum == null || currency == null) {
            return Dimension.unavailable(SALARY_WEIGHT, "Add a minimum salary in match preferences");
        }
        BigDecimal top = job.getSalaryMax() != null ? job.getSalaryMax() : job.getSalaryMin();
        if (top == null || job.getCurrency() == null) {
            return Dimension.unavailable(SALARY_WEIGHT, "The posting states no salary");
        }
        if (!job.getCurrency().equals(currency)) {
            return Dimension.unavailable(SALARY_WEIGHT,
                    "The posting pays in " + job.getCurrency() + ", your minimum is in " + currency + "; not converted");
        }
        String amount = top.stripTrailingZeros().toPlainString() + " " + currency;
        String yours = minimum.stripTrailingZeros().toPlainString() + " " + currency;
        return top.compareTo(minimum) >= 0
                ? new Dimension(Status.MATCH, 100.0, SALARY_WEIGHT, "Up to " + amount + ", at or above your " + yours)
                : new Dimension(Status.NO_MATCH, 0.0, SALARY_WEIGHT, "Up to " + amount + ", below your " + yours);
    }

    /** REMOTE, HYBRID or ON_SITE from what the posting says; null when it does not say. */
    static String workModeOf(String description) {
        if (description == null) {
            return null;
        }
        if (HYBRID.matcher(description).find()) {
            return "HYBRID";
        }
        if (REMOTE.matcher(description).find()) {
            return "REMOTE";
        }
        return ON_SITE.matcher(description).find() ? "ON_SITE" : null;
    }

    private static String label(String mode) {
        return switch (mode) {
            case "REMOTE" -> "Remote";
            case "HYBRID" -> "Hybrid";
            default -> "On-site";
        };
    }

    private static String describe(Location location) {
        return String.join(", ", java.util.stream.Stream.of(location.getCity(), location.getState(), location.getCountry())
                .filter(part -> part != null && !part.isBlank()).toList());
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
