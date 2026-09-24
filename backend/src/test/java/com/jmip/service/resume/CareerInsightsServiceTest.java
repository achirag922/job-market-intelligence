package com.jmip.service.resume;

import com.jmip.ai.AiUnavailableException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.SkillTrendResponse;
import com.jmip.dto.analytics.SkillTrendResponse.SkillTrend;
import com.jmip.dto.analytics.TrendDirection;
import com.jmip.dto.resume.CareerInsightsResponse;
import com.jmip.dto.resume.CareerInsightsResponse.CategorySource;
import com.jmip.dto.resume.CareerInsightsResponse.DemandSkill;
import com.jmip.dto.resume.CareerInsightsResponse.FocusArea;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import com.jmip.service.analytics.CategoryAnalyticsService;
import com.jmip.service.analytics.SkillTrendService;
import com.jmip.service.assistant.AnswerGenerator;
import com.jmip.service.assistant.AssistantData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CareerInsightsServiceTest {

    private static final UUID RESUME_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String CATEGORY = "Data Engineer";

    @Mock
    private ResumeService resumeService;
    @Mock
    private ResumeMatchService resumeMatchService;
    @Mock
    private CategoryAnalyticsService categoryAnalyticsService;
    @Mock
    private SkillTrendService skillTrendService;
    @Mock
    private AnswerGenerator answerGenerator;

    private CareerInsightsService service;

    @BeforeEach
    void setUp() {
        service = new CareerInsightsService(resumeService, resumeMatchService, categoryAnalyticsService,
                skillTrendService, answerGenerator, new JobMapper());
    }

    @Test
    @DisplayName("splits the category's demand into strong skills and skill gaps by resume skill id")
    void strongSkillsAndGaps() {
        givenResume(skill(1L, "Python"), skill(3L, "Airflow"));
        givenRecommendations();
        givenCategorySkills(demand(1L, "Python", 80, 1), demand(2L, "SQL", 70, 2), demand(3L, "Airflow", 40, 3),
                demand(4L, "Spark", 30, 4));
        givenRisingTrends();

        CareerInsightsResponse insights = service.insights(RESUME_ID, CATEGORY, false);

        assertThat(insights.targetCategory()).isEqualTo(CATEGORY);
        assertThat(insights.categorySource()).isEqualTo(CategorySource.REQUESTED);
        assertThat(insights.resumeSkills()).extracting("name").containsExactly("Airflow", "Python");
        assertThat(insights.strongSkills()).extracting(DemandSkill::skill).containsExactly("Python", "Airflow");
        assertThat(insights.skillGaps()).extracting(DemandSkill::skill).containsExactly("SQL", "Spark");
        // The figures are the analytics service's own, not recomputed here.
        assertThat(insights.skillGaps().get(0).percentageOfJobs()).isEqualTo(70.0);
        assertThat(insights.skillGaps().get(0).rank()).isEqualTo(2);
    }

    @Test
    @DisplayName("high-demand skills are the category's top ten, each flagged when on the resume")
    void highDemandSkills() {
        givenResume(skill(11L, "Skill 11"), skill(3L, "Skill 3"));
        givenRecommendations();
        EntitySkillResponse[] rows = new EntitySkillResponse[12];
        for (int index = 0; index < rows.length; index++) {
            rows[index] = demand(index + 1L, "Skill " + (index + 1), 90 - index, index + 1);
        }
        givenCategorySkills(rows);
        givenRisingTrends();

        CareerInsightsResponse insights = service.insights(RESUME_ID, CATEGORY, false);

        assertThat(insights.highDemandSkills()).hasSize(CareerInsightsService.HIGH_DEMAND_LIMIT);
        assertThat(insights.highDemandSkills()).filteredOn(DemandSkill::onResume)
                .extracting(DemandSkill::skill).containsExactly("Skill 3");
        // Skill 11 is outside the top ten but still a category skill the resume has.
        assertThat(insights.strongSkills()).extracting(DemandSkill::skill).containsExactly("Skill 3", "Skill 11");
        // Gaps come only from the top ten.
        assertThat(insights.skillGaps()).hasSize(9).extracting(DemandSkill::skill).doesNotContain("Skill 12");
    }

    @Test
    @DisplayName("trending skills are rising market-wide trends restricted to the category's skills")
    void trendingSkills() {
        givenResume(skill(1L, "Python"));
        givenRecommendations();
        givenCategorySkills(demand(1L, "Python", 80, 1), demand(2L, "SQL", 70, 2));
        givenRisingTrends(trend(9L, "Figma", 6.0), trend(2L, "SQL", 4.5), trend(1L, "Python", 2.0));

        CareerInsightsResponse insights = service.insights(RESUME_ID, CATEGORY, false);

        assertThat(insights.trendingSkills()).extracting("skill").containsExactly("SQL", "Python");
        assertThat(insights.trendingSkills()).extracting("onResume").containsExactly(false, true);
        assertThat(insights.trendingSkills().get(0).changeInPercentagePoints()).isEqualTo(4.5);
        verify(skillTrendService).trends(CareerInsightsService.TREND_MONTHS, CareerInsightsService.TREND_MIN_JOBS,
                TrendDirection.RISING, 100);
    }

    @Test
    @DisplayName("focus areas put rising gaps first by size of rise, then the rest by demand rank")
    void focusAreaOrdering() {
        List<DemandSkill> gaps = List.of(
                new DemandSkill(2L, "SQL", null, 7, 70, 2, false),
                new DemandSkill(4L, "Spark", null, 3, 30, 4, false),
                new DemandSkill(5L, "dbt", null, 2, 20, 5, false),
                new DemandSkill(6L, "Kafka", null, 1, 10, 6, false));
        List<CareerInsightsResponse.TrendingSkill> trending = List.of(
                new CareerInsightsResponse.TrendingSkill(5L, "dbt", null, 5, 12, 7.0, false),
                new CareerInsightsResponse.TrendingSkill(4L, "Spark", null, 10, 13, 3.0, false));

        List<FocusArea> focus = CareerInsightsService.focusAreas(gaps, trending);

        assertThat(focus).extracting(FocusArea::skill).containsExactly("dbt", "Spark", "SQL", "Kafka");
        assertThat(focus.get(0).changeInPercentagePoints()).isEqualTo(7.0);
        assertThat(focus.get(2).changeInPercentagePoints()).isNull();
    }

    @Test
    @DisplayName("related jobs are the V6.3 recommendations in the target category")
    void relatedJobsFilteredByCategory() {
        givenResume(skill(1L, "Python"));
        givenRecommendations(recommendation(10L, "Backend Developer"), recommendation(11L, CATEGORY),
                recommendation(12L, null), recommendation(13L, CATEGORY));
        givenCategorySkills(demand(1L, "Python", 80, 1));
        givenRisingTrends();

        CareerInsightsResponse insights = service.insights(RESUME_ID, CATEGORY, false);

        assertThat(insights.recommendedJobs()).extracting(ResumeRecommendationResponse::jobId).containsExactly(11L, 13L);
        verify(resumeMatchService).recommend(RESUME_ID, CareerInsightsService.RECOMMENDATION_POOL);
    }

    @Test
    @DisplayName("without a category, the best recommendation's category is used")
    void categoryFromTopRecommendation() {
        givenResume(skill(1L, "Python"));
        givenRecommendations(recommendation(10L, null), recommendation(11L, CATEGORY));
        givenCategorySkills(demand(1L, "Python", 80, 1));
        givenRisingTrends();

        CareerInsightsResponse insights = service.insights(RESUME_ID, "  ", false);

        assertThat(insights.targetCategory()).isEqualTo(CATEGORY);
        assertThat(insights.categorySource()).isEqualTo(CategorySource.TOP_RECOMMENDATION);
    }

    @Test
    @DisplayName("no category and no recommendation gives empty sections with a note, not an error")
    void noCategoryAvailable() {
        givenResume();
        givenRecommendations();

        CareerInsightsResponse insights = service.insights(RESUME_ID, null, true);

        assertThat(insights.targetCategory()).isNull();
        assertThat(insights.highDemandSkills()).isEmpty();
        assertThat(insights.skillGaps()).isEmpty();
        assertThat(insights.recommendedJobs()).isEmpty();
        assertThat(insights.note()).isNotBlank();
        verifyNoInteractions(categoryAnalyticsService, skillTrendService, answerGenerator);
    }

    @Test
    @DisplayName("a resume with no skills has every high-demand skill as a gap and nothing strong")
    void resumeWithoutSkills() {
        givenResume();
        givenRecommendations();
        givenCategorySkills(demand(1L, "Python", 80, 1), demand(2L, "SQL", 70, 2));
        givenRisingTrends();

        CareerInsightsResponse insights = service.insights(RESUME_ID, CATEGORY, false);

        assertThat(insights.resumeSkills()).isEmpty();
        assertThat(insights.strongSkills()).isEmpty();
        assertThat(insights.skillGaps()).extracting(DemandSkill::skill).containsExactly("Python", "SQL");
        assertThat(insights.trendingSkills()).isEmpty();
    }

    @Test
    @DisplayName("an unknown category is the analytics service's 404")
    void unknownCategory() {
        givenResume(skill(1L, "Python"));
        givenRecommendations();
        when(categoryAnalyticsService.skillsForCategory(eq("Astronaut"), anyInt()))
                .thenThrow(ResourceNotFoundException.of("Job category", "Astronaut"));

        assertThatThrownBy(() -> service.insights(RESUME_ID, "Astronaut", false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the summary is requested only on demand, from the computed figures")
    void summaryUsesComputedFigures() {
        givenResume(skill(1L, "Python"));
        givenRecommendations();
        givenCategorySkills(demand(1L, "Python", 80, 1), demand(2L, "SQL", 70, 2));
        givenRisingTrends();
        when(answerGenerator.describe(anyString(), any())).thenReturn("Python is covered; SQL is not.");

        assertThat(service.insights(RESUME_ID, CATEGORY, false).summary()).isNull();
        verify(answerGenerator, never()).describe(anyString(), any());

        CareerInsightsResponse insights = service.insights(RESUME_ID, CATEGORY, true);

        assertThat(insights.summary()).isEqualTo("Python is covered; SQL is not.");
        ArgumentCaptor<AssistantData> data = ArgumentCaptor.forClass(AssistantData.class);
        verify(answerGenerator).describe(anyString(), data.capture());
        assertThat(data.getValue().rows()).hasSize(1);
        assertThat(data.getValue().rows().get(0).toString()).contains("SQL=70.0", "Python=80.0");
    }

    @Test
    @DisplayName("an unavailable AI provider leaves the insights intact with a note")
    void summaryUnavailable() {
        givenResume(skill(1L, "Python"));
        givenRecommendations();
        givenCategorySkills(demand(1L, "Python", 80, 1));
        givenRisingTrends();
        when(answerGenerator.describe(anyString(), any())).thenThrow(new AiUnavailableException("no key"));

        CareerInsightsResponse insights = service.insights(RESUME_ID, CATEGORY, true);

        assertThat(insights.summary()).isNull();
        assertThat(insights.note()).contains("unavailable");
        assertThat(insights.strongSkills()).hasSize(1);
    }

    // ------------------------------------------------------------------------ fixtures

    private void givenResume(Skill... skills) {
        Resume resume = new Resume(RESUME_ID, "resume.pdf", RESUME_ID + ".pdf", "application/pdf", 100,
                OffsetDateTime.now());
        resume.markCompleted("skills", new LinkedHashSet<>(List.of(skills)), OffsetDateTime.now());
        when(resumeService.requireCompletedResume(RESUME_ID)).thenReturn(resume);
    }

    private void givenRecommendations(ResumeRecommendationResponse... recommendations) {
        when(resumeMatchService.recommend(RESUME_ID, CareerInsightsService.RECOMMENDATION_POOL))
                .thenReturn(List.of(recommendations));
    }

    private void givenCategorySkills(EntitySkillResponse... rows) {
        when(categoryAnalyticsService.skillsForCategory(CATEGORY, CareerInsightsService.CATEGORY_SKILL_POOL))
                .thenReturn(List.of(rows));
    }

    private void givenRisingTrends(SkillTrend... trends) {
        when(skillTrendService.trends(anyInt(), anyInt(), eq(TrendDirection.RISING), anyInt()))
                .thenReturn(new SkillTrendResponse(null, List.of(trends)));
    }

    private static EntitySkillResponse demand(Long id, String name, double percentage, int rank) {
        return new EntitySkillResponse(id, name, null, Math.round(percentage / 10), percentage, rank);
    }

    private static SkillTrend trend(Long id, String name, double change) {
        return new SkillTrend(id, name, null, 10, 10, 10 + change, change, TrendDirection.RISING, List.of());
    }

    private static ResumeRecommendationResponse recommendation(Long jobId, String category) {
        return new ResumeRecommendationResponse(jobId, "Job " + jobId, "Acme Systems", null, category, 50.0,
                List.of(), List.of());
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
