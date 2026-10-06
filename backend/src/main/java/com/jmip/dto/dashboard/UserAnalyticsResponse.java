package com.jmip.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * V9.8: the signed-in user's own career analytics over a time range. Every figure is counted or
 * averaged from stored data; a figure that cannot be computed is absent rather than zero, and
 * nothing is forecast.
 *
 * @param range  7D, 30D, 90D, 1Y or ALL
 * @param from   first day of the range; absent for ALL without any data
 * @param bucket DAY, WEEK (starting Monday) or MONTH, the step of the time series
 * @param notes  what the analytics cannot show, and why
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserAnalyticsResponse(String range, LocalDate from, LocalDate to, String bucket, Kpis kpis,
                                    List<ActivityPoint> activity, Funnel funnel, Map<String, Long> statusBreakdown,
                                    List<ResumePoint> resumeTrend, List<SkillGap> missingSkills,
                                    Interviews interviews, Learning learning, Portfolio portfolio,
                                    List<String> insights, List<String> notes) {

    /** Counts are for the range; averages are absent when there is nothing to average. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Kpis(long jobsSaved, long applications, long interviews, long offers, Double averageMatch,
                       int interviewsCompleted, Double averageInterviewScore, long learningCompleted) {
    }

    /** Status changes recorded in one bucket: jobs saved, applied, moved to interview and to offer. */
    public record ActivityPoint(String label, long saved, long applied, long interviews, long offers) {
    }

    /**
     * Applications started in the range and how far they got. Rates are percentages, absent without a base.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Funnel(long applied, long interviewed, long offers, Double applyToInterviewRate,
                         Double interviewToOfferRate) {
    }

    /**
     * One resume version compared, now, with all saved jobs.
     *
     * @param averageMatch  mean skill-match percentage over the saved jobs, absent when none could be scored
     * @param missingSkills distinct skills those jobs ask for that this version does not show
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResumePoint(String title, LocalDate date, int skills, Double averageMatch, int missingSkills,
                              int jobsCompared) {
    }

    /** A skill the current resume lacks, and in how many of the range's saved jobs. */
    public record SkillGap(String skill, long jobs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Interviews(int completed, Double averageScore, Double firstScore, Double latestScore,
                             List<InterviewPoint> sessions) {
    }

    /** One completed interview; scores are 1 to 5 and absent when nothing of that kind was evaluated. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InterviewPoint(LocalDate date, String jobTitle, String interviewType, Double overall, Double technical,
                                 Double behavioral) {
    }

    /**
     * @param targetSkills        skills on the active career goal's roadmap, when there is a goal
     * @param targetSkillsCovered of those, the ones on the resume or completed in the plan
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Learning(int items, int completed, int inProgress, int notStarted, long startedInRange,
                           long completedInRange, Double completionRate, Integer targetSkills,
                           Integer targetSkillsCovered, List<LearningPoint> activity) {
    }

    public record LearningPoint(String label, long started, long completed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Portfolio(boolean exists, String visibility, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                            OffsetDateTime publishedAt) {
    }
}
