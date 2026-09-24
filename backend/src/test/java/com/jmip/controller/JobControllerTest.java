package com.jmip.controller;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.CompanyResponse;
import com.jmip.dto.ExperienceResponse;
import com.jmip.dto.JobDetailResponse;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.LocationResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.SalaryResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.service.JobService;
import com.jmip.service.analytics.SalaryAnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import com.jmip.config.SecurityConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
// Slices do not load @Configuration classes; without this the default security chain would lock every endpoint.
@Import(SecurityConfig.class)
// V6.10.3: the API requires a signed-in USER; these tests exercise behaviour behind that.
@WithMockUser(roles = "USER")
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    /** Needed by the controller for the salary-currency options; unused by these tests. */
    @MockitoBean
    private SalaryAnalyticsService salaryAnalyticsService;

    @Test
    @DisplayName("returns 200 and the paging envelope")
    void returnsPagedJobs() throws Exception {
        when(jobService.search(any(), any(), any())).thenReturn(new PagedResponse<>(
                List.of(sampleJob()), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Senior Backend Engineer"))
                .andExpect(jsonPath("$.content[0].company.name").value("Acme Systems"))
                .andExpect(jsonPath("$.content[0].location.displayName").value("Austin, Texas, United States"))
                .andExpect(jsonPath("$.content[0].salary.currency").value("USD"))
                .andExpect(jsonPath("$.content[0].skills[0].name").value("Java"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.first").value(true));
    }

    @Test
    @DisplayName("never leaks Spring's own page internals")
    void doesNotLeakPageInternals() throws Exception {
        when(jobService.search(any(), any(), any()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist())
                .andExpect(jsonPath("$.numberOfElements").doesNotExist());
    }

    @Test
    @DisplayName("binds every filter from the query string")
    void bindsAllFilters() throws Exception {
        when(jobService.search(any(), any(), any()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/jobs")
                        .param("title", "engineer")
                        .param("location", "Austin")
                        .param("company", "Acme")
                        .param("skill", "Java")
                        .param("employmentType", "FULL_TIME"))
                .andExpect(status().isOk());

        ArgumentCaptor<JobSearchCriteria> captor = ArgumentCaptor.forClass(JobSearchCriteria.class);
        verify(jobService).search(captor.capture(), any(Pageable.class), any());

        JobSearchCriteria criteria = captor.getValue();
        assertThat(criteria.title()).isEqualTo("engineer");
        assertThat(criteria.location()).isEqualTo("Austin");
        assertThat(criteria.company()).isEqualTo("Acme");
        assertThat(criteria.skill()).isEqualTo("Java");
        assertThat(criteria.employmentType()).isEqualTo("FULL_TIME");
    }

    @Test
    @DisplayName("treats a blank filter as absent")
    void blankFilterIsAbsent() throws Exception {
        when(jobService.search(any(), any(), any()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/jobs").param("title", "   ")).andExpect(status().isOk());

        ArgumentCaptor<JobSearchCriteria> captor = ArgumentCaptor.forClass(JobSearchCriteria.class);
        verify(jobService).search(captor.capture(), any(Pageable.class), any());
        assertThat(captor.getValue().title()).isNull();
    }

    @Test
    @DisplayName("passes page and size through to the service")
    void passesPaging() throws Exception {
        when(jobService.search(any(), any(), any()))
                .thenReturn(new PagedResponse<>(List.of(), 3, 5, 0, 0, false, true));

        mockMvc.perform(get("/api/jobs").param("page", "3").param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobService).search(any(JobSearchCriteria.class), captor.capture(), any());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(3);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("rejects an employment type outside the vocabulary with 400")
    void rejectsUnknownEmploymentType() throws Exception {
        mockMvc.perform(get("/api/jobs").param("employmentType", "GIG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("employmentType"));
    }

    @Test
    @DisplayName("returns 200 and the full record for a known id")
    void returnsJobDetail() throws Exception {
        when(jobService.findById(1L)).thenReturn(new JobDetailResponse(
                1L, "Senior Backend Engineer",
                new CompanyResponse(1L, "Acme Systems", "Software", null),
                new LocationResponse(1L, "Austin", "Texas", "United States", "Austin, Texas, United States"),
                "We build services.", "FULL_TIME",
                new ExperienceResponse(5, 9),
                new SalaryResponse(new BigDecimal("150000"), new BigDecimal("190000"), "USD"),
                LocalDate.of(2026, 8, 13), "itest", "https://example.invalid/1",
                List.of(new SkillResponse(1L, "Java", "LANGUAGE")), null, null, null));

        mockMvc.perform(get("/api/jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("We build services."))
                .andExpect(jsonPath("$.source").value("itest"));
    }

    @Test
    @DisplayName("returns 404 with a structured body for an unknown id")
    void unknownJobReturnsNotFound() throws Exception {
        when(jobService.findById(999L)).thenThrow(ResourceNotFoundException.of("Job", 999L));

        mockMvc.perform(get("/api/jobs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Job not found: 999"))
                .andExpect(jsonPath("$.path").value("/api/jobs/999"));
    }

    @Test
    @DisplayName("returns 400 when the id is not a number")
    void nonNumericIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/jobs/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private static JobSummaryResponse sampleJob() {
        return new JobSummaryResponse(
                1L, "Senior Backend Engineer",
                new CompanyResponse(1L, "Acme Systems", "Software", null),
                new LocationResponse(1L, "Austin", "Texas", "United States", "Austin, Texas, United States"),
                "FULL_TIME",
                new ExperienceResponse(5, 9),
                new SalaryResponse(new BigDecimal("150000"), new BigDecimal("190000"), "USD"),
                LocalDate.of(2026, 8, 13),
                "Backend Developer",
                List.of(new SkillResponse(1L, "Java", "LANGUAGE")));
    }
}
