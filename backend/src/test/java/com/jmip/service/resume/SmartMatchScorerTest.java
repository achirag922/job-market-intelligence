package com.jmip.service.resume;

import com.jmip.dto.resume.MatchBreakdown;
import com.jmip.dto.resume.MatchBreakdown.Status;
import com.jmip.dto.resume.MatchPreferences;
import com.jmip.entity.Job;
import com.jmip.entity.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** V9.3: required vs optional skills from the posting's wording, goal alignment, role and skill preferences. */
class SmartMatchScorerTest {

    private final JobMatchScorer scorer = new JobMatchScorer();
    private static final Skill JAVA = skill(1, "Java");
    private static final Skill KUBERNETES = skill(2, "Kubernetes");
    private static final Skill DOCKER = skill(3, "Docker");

    @Test
    @DisplayName("a skill named only as nice to have is optional: shown, but it never lowers the skill score")
    void optionalSkills() {
        Job job = job("Build services in Java. Kubernetes is a plus.", "Backend Developer", JAVA, KUBERNETES);
        MatchBreakdown breakdown = scorer.score(Set.of(1L), job, MatchPreferences.NONE, JobMatchScorer.GoalContext.NONE);

        assertThat(breakdown.skills().score()).isEqualTo(100.0);
        assertThat(breakdown.skills().detail()).isEqualTo("1 of 1 required skills on your resume; 0 of 1 nice-to-have");
        assertThat(breakdown.optionalSkills()).containsExactly("Kubernetes");
        assertThat(breakdown.missingRequiredSkills()).isEmpty();

        MatchBreakdown missing = scorer.score(Set.of(2L), job, MatchPreferences.NONE, JobMatchScorer.GoalContext.NONE);
        assertThat(missing.skills().score()).isZero();
        assertThat(missing.missingRequiredSkills()).containsExactly("Java");
    }

    @Test
    @DisplayName("a nice-to-have section marks its skills optional; a skill also required elsewhere stays required")
    void sections() {
        Job job = job("Requirements:\nJava services.\n\nNice to have:\nDocker and Kubernetes\n", "Backend Developer",
                JAVA, KUBERNETES, DOCKER);
        MatchBreakdown breakdown = scorer.score(Set.of(1L), job, MatchPreferences.NONE, JobMatchScorer.GoalContext.NONE);
        assertThat(breakdown.optionalSkills()).containsExactly("Docker", "Kubernetes");
        assertThat(breakdown.skills().score()).isEqualTo(100.0);

        Job both = job("Docker is a plus. You run Docker in production.", null, DOCKER);
        assertThat(scorer.score(Set.of(), both, MatchPreferences.NONE, JobMatchScorer.GoalContext.NONE).optionalSkills()).isEmpty();
    }

    @Test
    @DisplayName("without any wording to go on, every listed skill is required, exactly as in V8.3")
    void defaultRequired() {
        Job job = job("Build services.", null, JAVA, DOCKER);
        MatchBreakdown breakdown = scorer.score(Set.of(1L), job, MatchPreferences.NONE, JobMatchScorer.GoalContext.NONE);
        assertThat(breakdown.skills().score()).isEqualTo(50.0);
        assertThat(breakdown.overallPercentage()).isEqualTo(50.0);
        assertThat(breakdown.optionalSkills()).isEmpty();
        assertThat(breakdown.missingRequiredSkills()).containsExactly("Docker");
    }

    @Test
    @DisplayName("career goal: same category matches, roadmap skills partly align, otherwise a mismatch; no goal is unavailable")
    void careerGoal() {
        JobMatchScorer.GoalContext goal = new JobMatchScorer.GoalContext("Backend Developer", "Backend Developer", Set.of(3L));
        assertThat(scorer.careerGoal(job("x", "Backend Developer", JAVA), goal).status()).isEqualTo(Status.MATCH);
        var partial = scorer.careerGoal(job("x", "DevOps Engineer", JAVA, DOCKER), goal);
        assertThat(partial.status()).isEqualTo(Status.PARTIAL);
        assertThat(partial.score()).isEqualTo(50.0);
        assertThat(partial.detail()).isEqualTo("1 of its 2 skills are on your Backend Developer roadmap");
        assertThat(scorer.careerGoal(job("x", "Data Analyst", JAVA), goal).status()).isEqualTo(Status.NO_MATCH);
        assertThat(scorer.careerGoal(job("x", null), goal).status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(scorer.careerGoal(job("x", "Backend Developer"), JobMatchScorer.GoalContext.NONE).status())
                .isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("preferred roles and skills join the overall score; unset ones are unavailable and do not count")
    void preferences() {
        Job job = job("Build services. Fully remote.", "Backend Developer", JAVA, DOCKER);
        MatchPreferences prefs = new MatchPreferences(null, null, "REMOTE", null, null, List.of("Data Analyst"),
                List.of("docker"), null, null);
        MatchBreakdown breakdown = scorer.score(Set.of(1L), job, prefs,
                new JobMatchScorer.GoalContext("Backend Developer", "Backend Developer", Set.of()));

        assertThat(breakdown.role().status()).isEqualTo(Status.NO_MATCH);
        assertThat(breakdown.preferredSkills().detail()).isEqualTo("Has your preferred skill: Docker");
        assertThat(breakdown.careerGoal().status()).isEqualTo(Status.MATCH);
        // skills 50*60 + work mode 100*10 + goal 100*10 + role 0*5 + preferred skills 100*5 = 5500 over 90
        assertThat(breakdown.overallPercentage()).isEqualTo(61.1);
        assertThat(breakdown.reasons()).hasSize(5).first().asString().startsWith("Skills (weight 60)");

        MatchBreakdown none = scorer.score(Set.of(1L), job, MatchPreferences.NONE, JobMatchScorer.GoalContext.NONE);
        assertThat(List.of(none.role(), none.preferredSkills(), none.careerGoal(), none.experience()))
                .allSatisfy(d -> assertThat(d.status()).isEqualTo(Status.UNAVAILABLE));
    }

    private static Job job(String description, String category, Skill... skills) {
        Job job = instance(Job.class);
        set(job, "description", description);
        set(job, "jobCategory", category);
        set(job, "skills", new LinkedHashSet<>(List.of(skills)));
        return job;
    }

    private static Skill skill(long id, String name) {
        Skill skill = instance(Skill.class);
        set(skill, "id", id);
        set(skill, "name", name);
        return skill;
    }

    private static <T> T instance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
