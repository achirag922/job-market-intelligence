package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

/**
 * A place a job is based. City and state are frequently unknown; country never is.
 */
@Entity
@Table(name = "locations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String city;

    private String state;

    @Column(nullable = false)
    private String country;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** "Austin, Texas, United States", skipping whatever the source did not supply. */
    public String displayName() {
        return Stream.of(city, state, country)
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse(country);
    }
}
