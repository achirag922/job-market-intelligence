package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One piece of evidence behind a job's category.
 *
 * <p>Read-only here: the ETL writes these rows. The composite key is the whole row, so it
 * is mapped with an id class rather than a surrogate key.
 */
@Entity
@Table(name = "job_classification_signals")
@IdClass(JobClassificationSignal.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobClassificationSignal {

    @Id
    @Column(name = "job_id")
    private Long jobId;

    @Id
    @Column(name = "signal_type")
    private String signalType;

    @Id
    @Column(name = "signal_value")
    private String signalValue;

    @Column(nullable = false)
    private BigDecimal weight;

    /** Composite key: job, kind of signal, and the matched value. */
    public static class Key implements Serializable {
        private Long jobId;
        private String signalType;
        private String signalValue;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(jobId, key.jobId)
                    && Objects.equals(signalType, key.signalType)
                    && Objects.equals(signalValue, key.signalValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, signalType, signalValue);
        }
    }
}
