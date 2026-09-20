package com.jmip.service;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.entity.Job;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final JobSearchCriteria NO_FILTERS =
            new JobSearchCriteria(null, null, null, null, null, null);

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobMapper jobMapper;

    @InjectMocks
    private JobService jobService;

    @Test
    @DisplayName("returns an empty page without asking for skills")
    void emptyPageSkipsSkillLookup() {
        when(jobRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PagedResponse<JobSummaryResponse> result = jobService.search(NO_FILTERS, PageRequest.of(0, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        // No page rows means no point querying for their skills.
        verify(jobRepository, never()).findSkillsForJobs(anyCollection());
    }

    @Test
    @DisplayName("loads skills for the whole page in a single query")
    void loadsSkillsInOneQuery() {
        Page<Job> page = new PageImpl<>(List.of(job(1L), job(2L), job(3L)), PageRequest.of(0, 20), 3);
        when(jobRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        when(jobRepository.findSkillsForJobs(anyCollection())).thenReturn(List.of());

        jobService.search(NO_FILTERS, PageRequest.of(0, 20));

        // Three jobs, one skill query, not three.
        verify(jobRepository).findSkillsForJobs(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("carries paging metadata into the response envelope")
    void carriesPagingMetadata() {
        Page<Job> page = new PageImpl<>(List.of(job(1L)), PageRequest.of(2, 5), 42);
        when(jobRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        when(jobRepository.findSkillsForJobs(anyCollection())).thenReturn(List.of());

        PagedResponse<JobSummaryResponse> result = jobService.search(NO_FILTERS, PageRequest.of(2, 5));

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(42);
        assertThat(result.totalPages()).isEqualTo(9);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isFalse();
    }

    @Test
    @DisplayName("rejects a sort on a field that is not sortable")
    void rejectsUnsortableField() {
        assertThatThrownBy(() -> jobService.search(NO_FILTERS, PageRequest.of(0, 20, Sort.by("description"))))
                .isInstanceOf(com.jmip.common.exception.InvalidRequestException.class);

        verify(jobRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    @DisplayName("a missing job is a 404, not an empty response")
    void missingJobIsNotFound() {
        when(jobRepository.findDetailById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found: 99");
    }

    /** Entities have no public constructor or setters, so a stub is built reflectively. */
    private static Job job(Long id) {
        try {
            var constructor = Job.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Job job = constructor.newInstance();
            var field = Job.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(job, id);
            return job;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build a Job stub", e);
        }
    }
}
