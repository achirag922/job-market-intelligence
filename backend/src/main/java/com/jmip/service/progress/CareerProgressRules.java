package com.jmip.service.progress;

import com.jmip.dto.progress.CareerProgressDtos.Component;
import com.jmip.dto.progress.CareerProgressDtos.Readiness;
import com.jmip.dto.progress.CareerProgressDtos.Streak;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * V9.13: the career readiness score and streaks, as pure functions of facts gathered elsewhere.
 *
 * <p>The score is 100 points over seven components. Volume is capped everywhere (three practice
 * interviews, three applications a month, five resume skills), so repeating an action past what is
 * useful earns nothing, and streaks are not part of the score at all.
 */
public final class CareerProgressRules {

    private CareerProgressRules() {
    }

    /**
     * Everything the score needs.
     *
     * @param roadmapPercent share of the active goal's roadmap skills covered, null without a goal or roadmap
     */
    public record Inputs(boolean experienceAndSkills, boolean jobPreferences, boolean activeGoal,
                         boolean hasResume, int resumeSkills, Double roadmapPercent,
                         int learningItems, int learningCompleted, int interviewsCompleted, Double averageInterviewScore,
                         int savedLast30Days, int appliedLast30Days,
                         boolean portfolioExists, boolean portfolioPublished, int portfolioSections) {
    }

    static final int PRACTICE_TARGET = 3;
    static final int APPLICATION_TARGET = 3;
    static final int RESUME_SKILL_TARGET = 5;
    static final int PORTFOLIO_SECTION_TARGET = 3;

    public static Readiness readiness(Inputs in) {
        List<Component> components = new ArrayList<>();

        int profile = (in.experienceAndSkills() ? 5 : 0) + (in.jobPreferences() ? 5 : 0) + (in.activeGoal() ? 5 : 0);
        components.add(component("profile", "Profile", profile, 15,
                count(in.experienceAndSkills(), in.jobPreferences(), in.activeGoal()) + " of 3: experience and skills, "
                        + "job preferences, an active career goal",
                !in.experienceAndSkills() ? "Add your experience and key skills"
                        : !in.jobPreferences() ? "Set your job preferences" : "Set a career goal"));

        int resume = in.hasResume() ? 8 + (int) Math.round(7.0 * Math.min(in.resumeSkills(), RESUME_SKILL_TARGET) / RESUME_SKILL_TARGET) : 0;
        components.add(component("resume", "Resume", resume, 15,
                in.hasResume() ? "Processed resume with " + in.resumeSkills() + " recognised skill" + plural(in.resumeSkills())
                        : "No processed resume yet",
                !in.hasResume() ? "Upload or build a resume"
                        : "List at least " + RESUME_SKILL_TARGET + " skills your resume can show"));

        int skills = in.roadmapPercent() == null ? 0 : (int) Math.round(20 * Math.min(in.roadmapPercent(), 100) / 100);
        components.add(component("skills", "Skill gap", skills, 20,
                in.roadmapPercent() == null ? "No career-goal roadmap to measure against"
                        : format(in.roadmapPercent()) + "% of your target role's roadmap skills covered",
                in.roadmapPercent() == null ? "Set a career goal to see your skill gap"
                        : "Learn the next skill on your roadmap"));

        int learning = in.learningItems() == 0 ? 0 : (int) Math.round(15.0 * in.learningCompleted() / in.learningItems());
        components.add(component("learning", "Learning", learning, 15,
                in.learningItems() == 0 ? "No learning plan yet"
                        : in.learningCompleted() + " of " + in.learningItems() + " learning items completed",
                in.learningItems() == 0 ? "Plan a skill to learn" : "Complete a learning item"));

        int practice = (int) Math.round(10.0 * Math.min(in.interviewsCompleted(), PRACTICE_TARGET) / PRACTICE_TARGET);
        int quality = in.averageInterviewScore() == null ? 0 : (int) Math.round(5 * in.averageInterviewScore() / 5);
        components.add(component("interviews", "Interview preparation", practice + quality, 15,
                in.interviewsCompleted() == 0 ? "No completed practice interviews"
                        : in.interviewsCompleted() + " completed practice interview" + plural(in.interviewsCompleted())
                        + (in.averageInterviewScore() == null ? "" : ", average " + String.format(Locale.ROOT, "%.1f", in.averageInterviewScore()) + "/5"),
                in.interviewsCompleted() < PRACTICE_TARGET ? "Complete a practice interview"
                        : "Improve your interview scores with the feedback"));

        int search = (in.savedLast30Days() > 0 ? 3 : 0)
                + (int) Math.round(7.0 * Math.min(in.appliedLast30Days(), APPLICATION_TARGET) / APPLICATION_TARGET);
        components.add(component("jobSearch", "Job search activity", search, 10,
                in.savedLast30Days() + " job" + plural(in.savedLast30Days()) + " saved and " + in.appliedLast30Days()
                        + " application" + plural(in.appliedLast30Days()) + " in the last 30 days",
                in.savedLast30Days() == 0 ? "Save a job that interests you" : "Apply to a job that matches you"));

        int portfolio = in.portfolioExists() ? 4 + (in.portfolioPublished() ? 3 : 0)
                + (int) Math.round(3.0 * Math.min(in.portfolioSections(), PORTFOLIO_SECTION_TARGET) / PORTFOLIO_SECTION_TARGET) : 0;
        components.add(component("portfolio", "Portfolio", portfolio, 10,
                !in.portfolioExists() ? "No portfolio yet"
                        : (in.portfolioPublished() ? "Published" : "Private") + ", " + in.portfolioSections() + " section"
                        + plural(in.portfolioSections()) + " filled",
                !in.portfolioExists() ? "Create your portfolio"
                        : in.portfolioSections() < PORTFOLIO_SECTION_TARGET ? "Fill in more portfolio sections" : "Publish your portfolio"));

        int score = components.stream().mapToInt(Component::points).sum();
        return new Readiness(score, level(score), components);
    }

    static String level(int score) {
        return score >= 85 ? "Job-ready" : score >= 60 ? "Strong progress" : score >= 30 ? "Building momentum" : "Getting started";
    }

    /** Weekly streak over the days with activity; see {@link Streak}. */
    public static Streak weeklyStreak(String key, String label, Collection<LocalDate> activeDays, LocalDate today) {
        TreeSet<LocalDate> weeks = new TreeSet<>();
        activeDays.forEach(day -> weeks.add(monday(day)));
        LocalDate thisWeek = monday(today);
        boolean activeThisWeek = weeks.contains(thisWeek);
        int current = 0;
        LocalDate cursor = activeThisWeek ? thisWeek : thisWeek.minusWeeks(1);
        while (weeks.contains(cursor)) {
            current++;
            cursor = cursor.minusWeeks(1);
        }
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate week : weeks) {
            run = previous != null && ChronoUnit.WEEKS.between(previous, week) == 1 ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = week;
        }
        LocalDate last = activeDays.stream().filter(day -> !day.isAfter(today)).max(LocalDate::compareTo).orElse(null);
        return new Streak(key, label, current, longest, activeThisWeek, last);
    }

    private static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static Component component(String key, String label, int points, int max, String detail, String hint) {
        return new Component(key, label, Math.min(points, max), max, detail, points >= max ? null : hint);
    }

    private static int count(boolean... flags) {
        int n = 0;
        for (boolean flag : flags) {
            n += flag ? 1 : 0;
        }
        return n;
    }

    private static String plural(int n) {
        return n == 1 ? "" : "s";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
