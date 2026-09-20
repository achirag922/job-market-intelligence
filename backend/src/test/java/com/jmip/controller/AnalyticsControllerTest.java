package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.service.AnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
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
    @DisplayName("returns skill demand with counts and percentages")
    void returnsSkillDemand() throws Exception {
        when(analyticsService.skillDemand(anyInt(), anyInt())).thenReturn(new PagedResponse<>(
                List.of(new SkillDemandResponse(1L, "SQL", "LANGUAGE", 34, 24.6)),
                0, 20, 1, 1, true, true));

        mockMvc.perform(get("/api/analytics/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].skill").value("SQL"))
                .andExpect(jsonPath("$.content[0].jobCount").value(34))
                .andExpect(jsonPath("$.content[0].percentageOfJobs").value(24.6));
    }

    @Test
    @DisplayName("defaults to the first page of twenty")
    void appliesDefaults() throws Exception {
        when(analyticsService.skillDemand(anyInt(), anyInt()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/analytics/skills")).andExpect(status().isOk());

        verify(analyticsService).skillDemand(0, 20);
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
}
