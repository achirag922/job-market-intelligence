package com.jmip.service.market;

import com.jmip.dto.market.MarketResponses.Scope;
import com.jmip.repository.MarketIntelligenceRepository;
import com.jmip.service.analytics.ExperienceBucket;
import com.jmip.service.analytics.SkillTrendService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketIntelligenceServiceTest {

    private final MarketIntelligenceRepository repository = mock(MarketIntelligenceRepository.class);
    private final MarketIntelligenceService service = new MarketIntelligenceService(repository, mock(SkillTrendService.class));

    private static Scope scope(LocalDate earliest, LocalDate latest) {
        return new Scope(null, null, null, null, latest, 10, 10, earliest, latest);
    }

    @Test
    @DisplayName("every month between the first and last covered is listed, so quiet months show as zero")
    void monthsAreContinuous() {
        assertThat(MarketIntelligenceService.months(scope(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 2, 1))))
                .containsExactly(LocalDate.of(2025, 11, 1), LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 2, 1));
        assertThat(MarketIntelligenceService.months(scope(null, null))).isEmpty();
    }

    @Test
    @DisplayName("a period of N months ends at the newest posting in the data, not at today's date")
    void periodIsAnchoredToTheData() {
        when(repository.latestPostedDate()).thenReturn(LocalDate.of(2026, 9, 12));

        MarketFilter filter = service.filter(" Backend Developer ", " ", "2-5", 3);

        assertThat(filter.from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(filter.category()).isEqualTo("Backend Developer");
        assertThat(filter.location()).isNull();
        assertThat(filter.experience()).isEqualTo(ExperienceBucket.TWO_TO_FIVE);
        assertThat(service.filter(null, null, null, null).narrowsPostings()).isFalse();
    }

    @Test
    @DisplayName("with no dated postings at all, a period filter restricts nothing rather than guessing a date")
    void noDatedPostings() {
        when(repository.latestPostedDate()).thenReturn(null);
        assertThat(service.filter(null, null, null, 6).from()).isNull();
    }
}
