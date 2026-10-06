package com.jmip.service.dashboard;

import com.jmip.dto.CompanyResponse;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.dashboard.DashboardResponse.ApplicationSection;
import com.jmip.dto.dashboard.DashboardResponse.FunnelStage;
import com.jmip.dto.dashboard.DashboardResponse.MatchSummary;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.dto.saved.SavedJobResponse;
import com.jmip.entity.ApplicationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardServiceTest {

    private static ResumeRecommendationResponse recommendation(double match) {
        return new ResumeRecommendationResponse(1L, "Engineer", "Acme", null, "Backend", match, List.of(), List.of());
    }

    private static SavedJobResponse saved(ApplicationStatus status) {
        JobSummaryResponse job = new JobSummaryResponse(1L, "Engineer", new CompanyResponse(1L, "Acme", null, null),
                null, null, null, null, null, null, List.of());
        return new SavedJobResponse(UUID.randomUUID(), job, status, "private", OffsetDateTime.now(), null, OffsetDateTime.now());
    }

    @Test
    @DisplayName("the match summary is the best and the mean of the recommendations, and empty without any")
    void matchSummary() {
        MatchSummary summary = DashboardService.matchSummary(List.of(recommendation(100), recommendation(50), recommendation(33.3)));
        assertThat(summary.jobsCompared()).isEqualTo(3);
        assertThat(summary.topMatchPercentage()).isEqualTo(100.0);
        assertThat(summary.averageMatchPercentage()).isEqualTo(61.1);

        MatchSummary none = DashboardService.matchSummary(List.of());
        assertThat(none.jobsCompared()).isZero();
        assertThat(none.averageMatchPercentage()).isNull();
    }

    @Test
    @DisplayName("application counts cover every status and the funnel follows the open pipeline")
    void applications() {
        ApplicationSection section = DashboardService.applicationSection(List.of(saved(ApplicationStatus.SAVED),
                saved(ApplicationStatus.APPLIED), saved(ApplicationStatus.APPLIED), saved(ApplicationStatus.OFFER),
                saved(ApplicationStatus.WITHDRAWN)));

        assertThat(section.total()).isEqualTo(5);
        assertThat(section.applied()).isEqualTo(2);
        assertThat(section.withdrawn()).isEqualTo(1);
        assertThat(section.funnel()).extracting(FunnelStage::stage).containsExactly(ApplicationStatus.SAVED,
                ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER);
        assertThat(section.funnel()).extracting(FunnelStage::jobs).containsExactly(1L, 2L, 0L, 1L);
        assertThat(section.recent()).hasSize(5);
    }
}
