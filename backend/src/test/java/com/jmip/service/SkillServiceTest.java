package com.jmip.service;

import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.repository.projection.SkillDemandRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobMapper jobMapper;

    @InjectMocks
    private SkillService skillService;

    @Test
    @DisplayName("computes each skill's share of all postings")
    void computesPercentageOfJobs() {
        when(jobRepository.count()).thenReturn(200L);
        when(skillRepository.findTopSkills(any())).thenReturn(List.of(
                new SkillDemandRow(1L, "Java", "LANGUAGE", 100),
                new SkillDemandRow(2L, "SQL", "LANGUAGE", 50),
                new SkillDemandRow(3L, "Kafka", "DATA", 1)));

        List<SkillDemandResponse> top = skillService.top(10);

        assertThat(top).extracting(SkillDemandResponse::skill).containsExactly("Java", "SQL", "Kafka");
        assertThat(top).extracting(SkillDemandResponse::percentageOfJobs).containsExactly(50.0, 25.0, 0.5);
    }

    @Test
    @DisplayName("passes the requested limit down as a page size")
    void passesLimitAsPageSize() {
        when(jobRepository.count()).thenReturn(10L);
        when(skillRepository.findTopSkills(any())).thenReturn(List.of());

        skillService.top(5);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(skillRepository).findTopSkills(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("percentages are rounded to one decimal place")
    void roundsToOneDecimalPlace() {
        // 1 of 3 is 33.333..., which must not leak floating point noise into the response.
        assertThat(SkillService.percentageOf(1, 3)).isEqualTo(33.3);
        assertThat(SkillService.percentageOf(2, 3)).isEqualTo(66.7);
    }

    @Test
    @DisplayName("an empty database yields zero percent rather than a division by zero")
    void handlesEmptyDatabase() {
        assertThat(SkillService.percentageOf(0, 0)).isZero();
    }

    @Test
    @DisplayName("a skill present on every posting is 100 percent")
    void universalSkillIsOneHundredPercent() {
        assertThat(SkillService.percentageOf(138, 138)).isEqualTo(100.0);
    }
}
