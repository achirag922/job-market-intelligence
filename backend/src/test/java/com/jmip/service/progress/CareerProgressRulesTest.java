package com.jmip.service.progress;

import com.jmip.dto.progress.CareerProgressDtos.Component;
import com.jmip.dto.progress.CareerProgressDtos.Readiness;
import com.jmip.dto.progress.CareerProgressDtos.Streak;
import com.jmip.service.progress.CareerProgressRules.Inputs;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** V9.13: the readiness score and weekly streaks, without a database. */
class CareerProgressRulesTest {

    private static final Inputs EMPTY = new Inputs(false, false, false, false, 0, null, 0, 0, 0, null, 0, 0, false, false, 0);

    private static Map<String, Integer> points(Readiness readiness) {
        return readiness.components().stream().collect(Collectors.toMap(Component::key, Component::points));
    }

    @Test
    void nothingYetScoresZeroAndSaysWhatToDoFirst() {
        Readiness readiness = CareerProgressRules.readiness(EMPTY);
        assertThat(readiness.score()).isZero();
        assertThat(readiness.level()).isEqualTo("Getting started");
        assertThat(readiness.components()).hasSize(7).allSatisfy(component -> assertThat(component.hint()).isNotBlank());
        assertThat(readiness.components()).extracting(Component::maxPoints).containsExactly(15, 15, 20, 15, 15, 10, 10);
        assertThat(readiness.components().get(2).hint()).isEqualTo("Set a career goal to see your skill gap");
    }

    @Test
    void everythingDoneScoresOneHundredWithNoHints() {
        Readiness readiness = CareerProgressRules.readiness(
                new Inputs(true, true, true, true, 8, 100.0, 4, 4, 3, 5.0, 2, 3, true, true, 5));
        assertThat(readiness.score()).isEqualTo(100);
        assertThat(readiness.level()).isEqualTo("Job-ready");
        assertThat(readiness.components()).allSatisfy(component -> assertThat(component.hint()).isNull());
    }

    @Test
    void partialProgressIsWeighedComponentByComponent() {
        Readiness readiness = CareerProgressRules.readiness(
                new Inputs(true, false, true, true, 2, 50.0, 2, 1, 1, 3.8, 0, 1, true, false, 1));
        assertThat(points(readiness)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "profile", 10,       // 5 + 0 + 5
                "resume", 11,        // 8 + round(7 * 2/5)
                "skills", 10,        // 20 * 50%
                "learning", 8,       // round(15 * 1/2)
                "interviews", 7,     // round(10 * 1/3) + round(3.8)
                "jobSearch", 2,      // 0 + round(7 * 1/3)
                "portfolio", 5));    // 4 + 0 + round(3 * 1/3)
        assertThat(readiness.score()).isEqualTo(53);
        assertThat(readiness.level()).isEqualTo("Building momentum");
        assertThat(readiness.components().get(0).hint()).isEqualTo("Set your job preferences");
    }

    @Test
    void repeatingAnActionPastWhatHelpsEarnsNothing() {
        Inputs three = new Inputs(false, false, false, false, 0, null, 0, 0, 3, null, 1, 3, false, false, 0);
        Inputs spam = new Inputs(false, false, false, false, 0, null, 0, 0, 40, null, 200, 90, false, false, 0);
        assertThat(CareerProgressRules.readiness(spam).score()).isEqualTo(CareerProgressRules.readiness(three).score());
        assertThat(points(CareerProgressRules.readiness(spam)).get("jobSearch")).isEqualTo(10);
    }

    @Test
    void weeklyStreakCountsConsecutiveWeeksAndKeepsTheCurrentWeekOpen() {
        LocalDate wednesday = LocalDate.of(2026, 10, 7);
        // Active in each of the three weeks before this one, nothing yet this week: the streak still stands.
        Streak open = CareerProgressRules.weeklyStreak("learning", "Learning",
                List.of(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 29)), wednesday);
        assertThat(open.currentWeeks()).isEqualTo(3);
        assertThat(open.activeThisWeek()).isFalse();
        assertThat(open.lastActiveOn()).isEqualTo(LocalDate.of(2026, 9, 29));

        // Several actions in one week count once; a missed week breaks the run.
        Streak broken = CareerProgressRules.weeklyStreak("jobSearch", "Job search", List.of(LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 5)), wednesday);
        assertThat(broken.currentWeeks()).isEqualTo(1);
        assertThat(broken.activeThisWeek()).isTrue();
        assertThat(broken.longestWeeks()).isEqualTo(2);

        Streak none = CareerProgressRules.weeklyStreak("interviews", "Interview practice", List.of(), wednesday);
        assertThat(none.currentWeeks()).isZero();
        assertThat(none.longestWeeks()).isZero();
        assertThat(none.lastActiveOn()).isNull();
    }
}
