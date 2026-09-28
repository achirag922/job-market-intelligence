package com.jmip.service.saved;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.Job;
import com.jmip.entity.SavedJob;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import com.jmip.repository.SavedJobRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SavedJobServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);

    private final SavedJobRepository repository = mock(SavedJobRepository.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final SavedJobService service =
            new SavedJobService(repository, jobRepository, mock(JobMapper.class), currentUser, CLOCK);

    private static SavedJob saved() {
        return new SavedJob(UUID.randomUUID(), OWNER, mock(Job.class), OffsetDateTime.now(CLOCK));
    }

    @Test
    @DisplayName("the application date is set on leaving SAVED, kept through later moves, and cleared back at SAVED")
    void applicationDate() {
        SavedJob saved = saved();
        OffsetDateTime applied = OffsetDateTime.now(CLOCK).plusDays(1);
        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.SAVED);
        assertThat(saved.getAppliedAt()).isNull();

        saved.changeStatus(ApplicationStatus.APPLIED, applied);
        saved.changeStatus(ApplicationStatus.INTERVIEW, applied.plusDays(3));
        saved.changeStatus(ApplicationStatus.OFFER, applied.plusDays(9));

        assertThat(saved.getAppliedAt()).isEqualTo(applied);
        assertThat(saved.getUpdatedAt()).isEqualTo(applied.plusDays(9));

        saved.changeStatus(ApplicationStatus.SAVED, applied.plusDays(10));
        assertThat(saved.getAppliedAt()).isNull();
    }

    @Test
    @DisplayName("saving a job that is already saved returns it without inserting")
    void saveIsIdempotent() {
        when(currentUser.requireId()).thenReturn(OWNER);
        when(repository.findByUserIdAndJobId(OWNER, 7L)).thenReturn(Optional.of(saved()));

        assertThat(service.save(7L).created()).isFalse();
        verify(repository, never()).insertIfAbsent(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("a new save inserts for the session's account; an unknown job is not found")
    void saveUsesSessionOwner() {
        when(currentUser.requireId()).thenReturn(OWNER);
        when(repository.findByUserIdAndJobId(OWNER, 7L)).thenReturn(Optional.empty(), Optional.of(saved()));
        when(jobRepository.existsById(7L)).thenReturn(true);
        when(repository.insertIfAbsent(any(), eq(OWNER), eq(7L), any())).thenReturn(1);

        assertThat(service.save(7L).created()).isTrue();

        when(jobRepository.existsById(8L)).thenReturn(false);
        when(repository.findByUserIdAndJobId(OWNER, 8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(8L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("saving is refused at the per-account limit")
    void saveRespectsLimit() {
        when(currentUser.requireId()).thenReturn(OWNER);
        when(repository.findByUserIdAndJobId(OWNER, 7L)).thenReturn(Optional.empty());
        when(jobRepository.existsById(7L)).thenReturn(true);
        when(repository.countByUserId(OWNER)).thenReturn((long) SavedJobService.MAX_SAVED_PER_ACCOUNT);

        assertThatThrownBy(() -> service.save(7L)).isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).insertIfAbsent(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("status, notes and delete look records up by id and the session's account only")
    void ownerScoped() {
        UUID id = UUID.randomUUID();
        when(currentUser.requireId()).thenReturn(OWNER);
        when(repository.findByIdAndUserId(id, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(id, ApplicationStatus.APPLIED)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.changeNotes(id, "x")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any());
    }
}
