package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.ExperienceDistributionResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.dto.analytics.SkillAnalyticsResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.service.AnalyticsService;
import com.jmip.service.analytics.CategoryAnalyticsService;
import com.jmip.service.analytics.SkillAnalyticsService;
import com.jmip.service.analytics.SkillTrendService;
import com.jmip.service.analytics.TitleAnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private SkillAnalyticsService skillAnalyticsService;

    @MockitoBean
    private SkillTrendService skillTrendService;

    @MockitoBean
    private CategoryAnalyticsService categoryAnalyticsService;

    @MockitoBean
    private TitleAnalyticsService titleAnalyticsService;

    @Test
    @DisplayName("returns the headline counts")
    void returnsOverview() throws Exception {
        when(analyticsService.overview()).thenReturn(new OverviewResponse(138, 35, 16, 27));

        mockMvc.perform(get("/api/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(138))
                .andExpect(jsonPath("$.totalCompanies").value(35))
                .andExpect(jsonPath("$.totalSkills").value(16))
                .andExpect(jsonPath("$.totalLocations").value(27));
    }

    @Test
    @DisplayName("returns skill demand with the scope it was measured over")
    void returnsSkillDemand() throws Exception {
        when(skillAnalyticsService.skillDemand(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SkillAnalyticsResponse(
                        new SkillAnalyticsResponse.Scope(138, null, null, null, null),
                        new PagedResponse<>(
                                List.of(new SkillDemandResponse(1L, "SQL", "LANGUAGE", 34, 24.6, 1)),
                                0, 20, 1, 1, true, true)));

        mockMvc.perform(get("/api/analytics/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(138))
                .andExpect(jsonPath("$.skills.content[0].skill").value("SQL"))
                .andExpect(jsonPath("$.skills.content[0].jobCount").value(34))
                .andExpect(jsonPath("$.skills.content[0].percentageOfJobs").value(24.6))
                .andExpect(jsonPath("$.skills.content[0].rank").value(1));
    }

    @Test
    @DisplayName("binds the analytics filters, parsing dates")
    void bindsFilters() throws Exception {
        when(skillAnalyticsService.skillDemand(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptySkills());

        mockMvc.perform(get("/api/analytics/skills")
                        .param("location", "Berlin")
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-06-30")
                        .param("title", "engineer"))
                .andExpect(status().isOk());

        verify(skillAnalyticsService).skillDemand(
                eq("Berlin"),
                eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 6, 30)),
                eq("engineer"),
                eq(0),
                eq(20));
    }

    @Test
    @DisplayName("rejects a malformed date with 400")
    void rejectsMalformedDate() throws Exception {
        mockMvc.perform(get("/api/analytics/skills").param("fromDate", "last-tuesday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("omitted filters reach the service as null rather than empty strings")
    void omittedFiltersAreNull() throws Exception {
        when(skillAnalyticsService.skillDemand(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptySkills());

        mockMvc.perform(get("/api/analytics/skills")).andExpect(status().isOk());

        verify(skillAnalyticsService).skillDemand(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("returns the experience distribution")
    void returnsExperienceDistribution() throws Exception {
        when(analyticsService.experienceDistribution()).thenReturn(
                new ExperienceDistributionResponse(100, List.of(
                        new ExperienceDistributionResponse.Bucket(
                                "ZERO_TO_TWO", "0–2 years", 0, 2, 25, 25.0),
                        new ExperienceDistributionResponse.Bucket(
                                "EIGHT_PLUS", "8+ years", 8, null, 10, 10.0))));

        mockMvc.perform(get("/api/analytics/experience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(100))
                .andExpect(jsonPath("$.buckets[0].bucket").value("ZERO_TO_TWO"))
                .andExpect(jsonPath("$.buckets[0].label").value("0–2 years"))
                .andExpect(jsonPath("$.buckets[0].jobCount").value(25))
                .andExpect(jsonPath("$.buckets[1].maxYearsExclusive").doesNotExist());
    }

    @Test
    @DisplayName("defaults to the first page of twenty")
    void appliesDefaults() throws Exception {
        when(skillAnalyticsService.skillDemand(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptySkills());

        mockMvc.perform(get("/api/analytics/skills")).andExpect(status().isOk());

        verify(skillAnalyticsService).skillDemand(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("rejects a page size beyond the cap with 400")
    void rejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/api/analytics/skills").param("size", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    @DisplayName("rejects a negative page number with 400")
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/analytics/skills").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private static SkillAnalyticsResponse emptySkills() {
        return new SkillAnalyticsResponse(
                new SkillAnalyticsResponse.Scope(0, null, null, null, null),
                new PagedResponse<>(List.of(), 0, 20, 0, 0, true, true));
    }
}
