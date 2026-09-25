package com.jmip.service.career;

import com.jmip.dto.SkillResponse;
import com.jmip.dto.career.RoadmapResponse.Progress;
import com.jmip.dto.career.RoadmapResponse.RoadmapSkill;
import com.jmip.dto.career.RoadmapResponse.Source;
import com.jmip.entity.SkillProgressStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapServiceTest {

    private static RoadmapService.Candidate market(long id, String name, double percentage, int rank) {
        return new RoadmapService.Candidate(new SkillResponse(id, name, "OTHER"), Source.MARKET_DEMAND, percentage, rank);
    }

    private static RoadmapService.Candidate chosen(long id, String name) {
        return new RoadmapService.Candidate(new SkillResponse(id, name, "OTHER"), Source.YOUR_CHOICE, null, null);
    }

    private static Map<Long, RoadmapService.Candidate> candidates(RoadmapService.Candidate... items) {
        Map<Long, RoadmapService.Candidate> map = new LinkedHashMap<>();
        for (RoadmapService.Candidate item : items) {
            map.put(item.skill().id(), item);
        }
        return map;
    }

    @Test
    @DisplayName("rising skills lead (biggest rise first), then demand rank, then the user's own choices")
    void priorityOrder() {
        List<RoadmapSkill> roadmap = RoadmapService.prioritise(
                candidates(market(1, "Docker", 60, 2), market(2, "Kafka", 20, 5), market(3, "Terraform", 40, 3),
                        chosen(4, "Go"), market(5, "Kubernetes", 30, 4)),
                Map.of(2L, 1.5, 5L, 4.0),
                Map.of(),
                "Backend Developer");

        assertThat(roadmap).extracting(RoadmapSkill::skill).containsExactly("Kubernetes", "Kafka", "Docker", "Terraform", "Go");
        assertThat(roadmap).extracting(RoadmapSkill::priority).containsExactly(1, 2, 3, 4, 5);
        assertThat(roadmap.get(0).reason()).isEqualTo(
                "In 30.0% of Backend Developer postings (rank 4); rising by 4.0 percentage points in recent postings");
        assertThat(roadmap.get(4).reason()).isEqualTo("You added this skill to the goal");
        assertThat(roadmap.get(4).percentageOfJobs()).isNull();
    }

    @Test
    @DisplayName("stored progress is applied; anything without progress is NOT_STARTED")
    void statuses() {
        List<RoadmapSkill> roadmap = RoadmapService.prioritise(
                candidates(market(1, "Docker", 60, 1), market(2, "Kafka", 20, 2)), Map.of(),
                Map.of(2L, SkillProgressStatus.COMPLETED), "Backend Developer");

        assertThat(roadmap).extracting(RoadmapSkill::status)
                .containsExactly(SkillProgressStatus.NOT_STARTED, SkillProgressStatus.COMPLETED);
    }

    @Test
    @DisplayName("progress counts resume skills and completed skills against every roadmap skill")
    void progress() {
        List<RoadmapSkill> roadmap = RoadmapService.prioritise(
                candidates(market(1, "Docker", 60, 1), market(2, "Kafka", 20, 2), chosen(3, "Go")), Map.of(),
                Map.of(1L, SkillProgressStatus.IN_PROGRESS, 3L, SkillProgressStatus.COMPLETED), "Backend Developer");

        Progress progress = RoadmapService.progress(2, roadmap);

        assertThat(progress.totalSkills()).isEqualTo(5);
        assertThat(progress.onResume()).isEqualTo(2);
        assertThat(progress.completed()).isEqualTo(1);
        assertThat(progress.inProgress()).isEqualTo(1);
        assertThat(progress.notStarted()).isEqualTo(1);
        assertThat(progress.percentComplete()).isEqualTo(60.0);
        assertThat(RoadmapService.progress(0, List.of()).percentComplete()).isNull();
    }
}
