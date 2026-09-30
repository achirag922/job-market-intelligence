package com.jmip.service.dashboard;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.dashboard.UserAnalyticsResponse.ActivityPoint;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Funnel;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Interviews;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Learning;
import com.jmip.entity.ApplicationStatus;
import com.jmip.repository.ApplicationEventRepository.Event;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V9.8: the time windows, buckets, funnel and insight wording, without a database. */
class UserAnalyticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private static OffsetDateTime daysAgo(int days) {
        return TODAY.minusDays(days).atTime(12, 0).atOffset(ZoneOffset.UTC);
    }

    private static Event event(UUID job, ApplicationStatus status, int daysAgo) {
        return new Event(job, status, daysAgo(daysAgo), false);
    }

    @Test
    void windowsHaveTheirOwnLengthAndStep() {
        assertThat(UserAnalyticsService.window("7D", TODAY, null).labels()).hasSize(7).last().isEqualTo("2026-09-30");
        assertThat(UserAnalyticsService.window("30D", TODAY, null).from()).isEqualTo(LocalDate.of(2026, 9, 1));
        UserAnalyticsService.Window quarter = UserAnalyticsService.window("90D", TODAY, null);
        assertThat(quarter.bucket()).isEqualTo("WEEK");
        assertThat(quarter.labels()).first().isEqualTo("2026-06-29"); // the Monday of the week of July 3
        UserAnalyticsService.Window year = UserAnalyticsService.window("1Y", TODAY, null);
        assertThat(year.labels()).hasSize(12).first().isEqualTo("2025-10");
        assertThat(UserAnalyticsService.window("ALL", TODAY, null).from()).isNull();
        assertThat(UserAnalyticsService.window("ALL", TODAY, null).labels()).isEmpty();
        assertThat(UserAnalyticsService.window("ALL", TODAY, TODAY.minusDays(10)).bucket()).isEqualTo("DAY");
        assertThat(UserAnalyticsService.window("ALL", TODAY, TODAY.minusDays(400)).bucket()).isEqualTo("MONTH");
        assertThatThrownBy(() -> UserAnalyticsService.window("2W", TODAY, null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void activityCountsEachStatusChangeInItsBucketAndKeepsEmptyBuckets() {
        UUID job = UUID.randomUUID();
        UserAnalyticsService.Window week = UserAnalyticsService.window("7D", TODAY, null);
        List<ActivityPoint> points = UserAnalyticsService.activity(week, List.of(event(job, ApplicationStatus.SAVED, 3),
                event(job, ApplicationStatus.APPLIED, 3), event(job, ApplicationStatus.INTERVIEW, 0),
                event(job, ApplicationStatus.REJECTED, 0)));
        assertThat(points).hasSize(7);
        assertThat(points.get(3)).isEqualTo(new ActivityPoint("2026-09-27", 1, 1, 0, 0));
        assertThat(points.get(6)).isEqualTo(new ActivityPoint("2026-09-30", 0, 0, 1, 0));
        assertThat(points.get(0)).isEqualTo(new ActivityPoint("2026-09-24", 0, 0, 0, 0));
    }

    @Test
    void funnelFollowsApplicationsStartedInTheRange() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        List<Event> history = List.of(
                event(a, ApplicationStatus.APPLIED, 20), event(a, ApplicationStatus.INTERVIEW, 10), event(a, ApplicationStatus.OFFER, 2),
                event(b, ApplicationStatus.APPLIED, 5), event(b, ApplicationStatus.INTERVIEW, 1),
                event(c, ApplicationStatus.SAVED, 4), event(c, ApplicationStatus.APPLIED, 3),
                event(old, ApplicationStatus.APPLIED, 60), event(old, ApplicationStatus.INTERVIEW, 5));
        Funnel funnel = UserAnalyticsService.funnel(history, UserAnalyticsService.window("30D", TODAY, null));
        assertThat(funnel).isEqualTo(new Funnel(3, 2, 1, 66.7, 50.0));

        Funnel none = UserAnalyticsService.funnel(List.of(event(c, ApplicationStatus.SAVED, 1)),
                UserAnalyticsService.window("30D", TODAY, null));
        assertThat(none).isEqualTo(new Funnel(0, 0, 0, null, null));
    }

    @Test
    void insightsStateOnlyWhatTheDataShows() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UserAnalyticsService.Window month = UserAnalyticsService.window("30D", TODAY, null);
        List<Event> history = List.of(event(a, ApplicationStatus.APPLIED, 40), event(a, ApplicationStatus.APPLIED, 2),
                event(b, ApplicationStatus.APPLIED, 1));
        List<String> insights = UserAnalyticsService.insights(month, history, UserAnalyticsService.funnel(history, month),
                List.of(), List.of(), 0, new Interviews(0, null, null, null, List.of()),
                new Learning(0, 0, 0, 0, 0, 0, null, null, null, List.of()));
        assertThat(insights).containsExactly("You applied to 2 jobs in this period, up from 1 in the period before.",
                "0 of 1 applications started in this period reached an interview (0.0%).");

        assertThat(UserAnalyticsService.insights(month, List.of(), new Funnel(0, 0, 0, null, null), List.of(), List.of(), 0,
                new Interviews(0, null, null, null, List.of()), new Learning(0, 0, 0, 0, 0, 0, null, null, null, List.of())))
                .isEmpty();
    }
}
