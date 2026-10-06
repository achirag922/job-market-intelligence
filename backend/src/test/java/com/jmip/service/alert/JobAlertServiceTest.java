package com.jmip.service.alert;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.alert.JobAlertRequest;
import com.jmip.entity.AlertFrequency;
import com.jmip.entity.JobAlert;
import com.jmip.repository.JobAlertRepository;
import com.jmip.service.auth.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobAlertServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);

    private final JobAlertRepository repository = mock(JobAlertRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final JobAlertService service = new JobAlertService(repository, currentUser, CLOCK);

    private static JobAlertRequest request() {
        return new JobAlertRequest(" Java in Berlin ", " backend ", "Software Engineering", " Berlin ", " UNSPECIFIED ",
                "Java", AlertFrequency.WEEKLY);
    }

    @Test
    @DisplayName("the request trims text, lower-cases experience and turns blanks into no filter")
    void requestNormalisation() {
        JobAlertRequest request = request();
        assertThat(request.name()).isEqualTo("Java in Berlin");
        assertThat(request.keywords()).isEqualTo("backend");
        assertThat(request.experience()).isEqualTo("unspecified");
        assertThat(request.isCriteriaGiven()).isTrue();

        JobAlertRequest empty = new JobAlertRequest("Name", " ", "", null, "  ", null, AlertFrequency.DAILY);
        assertThat(empty.keywords()).isNull();
        assertThat(empty.experience()).isNull();
        assertThat(empty.isCriteriaGiven()).isFalse();
    }

    @Test
    @DisplayName("an alert maps onto the existing job search's filters unchanged")
    void alertIsAJobSearch() {
        JobAlert alert = new JobAlert(UUID.randomUUID(), OWNER, request().toCriteria(), OffsetDateTime.now(CLOCK));

        JobSearchCriteria criteria = alert.toSearchCriteria();

        assertThat(criteria.q()).isEqualTo("backend");
        assertThat(criteria.category()).isEqualTo("Software Engineering");
        assertThat(criteria.location()).isEqualTo("Berlin");
        assertThat(criteria.skill()).isEqualTo("Java");
        assertThat(criteria.experience()).isEqualToIgnoringCase("unspecified");
        assertThat(criteria.title()).isNull();
        assertThat(criteria.company()).isNull();
    }

    @Test
    @DisplayName("create takes the owner from the session and starts active")
    void createUsesSessionOwner() {
        when(currentUser.requireId()).thenReturn(OWNER);
        when(repository.save(any(JobAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.create(request());

        assertThat(created.active()).isTrue();
        assertThat(created.frequency()).isEqualTo(AlertFrequency.WEEKLY);
        assertThat(created.createdAt()).isEqualTo(OffsetDateTime.now(CLOCK));
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(alert -> alert.isOwnedBy(OWNER)));
    }

    @Test
    @DisplayName("create is refused once the account has the maximum number of alerts")
    void createRespectsLimit() {
        when(currentUser.requireId()).thenReturn(OWNER);
        when(repository.countByUserId(OWNER)).thenReturn((long) JobAlertService.MAX_ALERTS_PER_ACCOUNT);

        assertThatThrownBy(() -> service.create(request())).isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("every lookup is scoped to the session's account; anything else is not found")
    void lookupsAreOwnerScoped() {
        UUID id = UUID.randomUUID();
        when(currentUser.requireId()).thenReturn(OWNER);
        when(repository.findByIdAndUserId(id, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setActive(id, false)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any());
    }
}
