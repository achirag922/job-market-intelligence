package com.jmip.service.resume;

import com.jmip.dto.LocationResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.entity.Company;
import com.jmip.entity.Job;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage for the bounded, shared V3 recommendation path. */
@ExtendWith(MockitoExtension.class)
class ResumeMatchServiceTest {

    @Mock
    private ResumeService resumeService;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobMapper jobMapper;

    @InjectMocks
    private ResumeMatchService matchService;

    @Test
    @DisplayName("keeps the database ranking and loads all recommended jobs in one detail query")
    void recommendsInRankedOrderWithoutPerJobQueries() {
        Skill java = skill(1L, "Java");
        Skill spring = skill(2L, "Spring Boot");
        Skill docker = skill(3L, "Docker");
        UUID id = UUID.randomUUID();
        Resume resume = resume(id, java, spring);
        Job lowerScore = job(3L, "Backend Engineer", java, docker);
        Job fullScore = job(4L, "Java Developer", java, spring);

        when(resumeService.requireCompletedResume(id)).thenReturn(resume);
        when(jobRepository.findRecommendationJobIds(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(4L, 3L));
        // Deliberately reverse hydration order: the service must restore the ranked ids.
        when(jobRepository.findRecommendationDetailsByIdIn(List.of(4L, 3L)))
                .thenReturn(List.of(lowerScore, fullScore));
        when(jobMapper.toSkill(any(Skill.class))).thenAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return new SkillResponse(skill.getId(), skill.getName(), null);
        });
        when(jobMapper.toLocation(any())).thenReturn(new LocationResponse(1L, "Austin", null, "United States", "Austin, United States"));

        List<ResumeRecommendationResponse> result = matchService.recommend(id, 2);

        assertThat(result).extracting(ResumeRecommendationResponse::jobId).containsExactly(4L, 3L);
        assertThat(result).extracting(ResumeRecommendationResponse::matchPercentage).containsExactly(100.0, 50.0);
        assertThat(result.get(1).missingSkills()).extracting(SkillResponse::name).containsExactly("Docker");

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findRecommendationJobIds(anyCollection(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(2);
        verify(jobRepository).findRecommendationDetailsByIdIn(List.of(4L, 3L));
        verify(jobRepository, never()).findDetailById(any());
    }

    @Test
    @DisplayName("a completed resume with no skills skips recommendation queries")
    void noResumeSkillsMeansNoRecommendations() {
        UUID id = UUID.randomUUID();
        when(resumeService.requireCompletedResume(id)).thenReturn(resume(id));

        assertThat(matchService.recommend(id, 10)).isEmpty();
        verify(jobRepository, never()).findRecommendationJobIds(anyCollection(), any(Pageable.class));
        verify(jobRepository, never()).findRecommendationDetailsByIdIn(anyCollection());
    }

    private static Resume resume(UUID id, Skill... skills) {
        Resume resume = new Resume(id, "resume.pdf", id + ".pdf", "application/pdf", 100, OffsetDateTime.now());
        resume.markCompleted("skills", new LinkedHashSet<>(List.of(skills)), OffsetDateTime.now());
        return resume;
    }

    private static Job job(Long id, String title, Skill... skills) {
        try {
            var constructor = Job.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Job job = constructor.newInstance();
            set(job, "id", id);
            set(job, "title", title);
            set(job, "company", company("Acme Systems"));
            set(job, "skills", new LinkedHashSet<>(List.of(skills)));
            return job;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build job fixture", e);
        }
    }

    private static Company company(String name) throws ReflectiveOperationException {
        var constructor = Company.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Company company = constructor.newInstance();
        set(company, "name", name);
        return company;
    }

    private static Skill skill(Long id, String name) {
        try {
            var constructor = Skill.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Skill skill = constructor.newInstance();
            set(skill, "id", id);
            set(skill, "name", name);
            return skill;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build skill fixture", e);
        }
    }

    private static void set(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
