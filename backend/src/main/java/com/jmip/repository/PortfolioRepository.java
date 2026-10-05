package com.jmip.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** V9.7: portfolios, one per account. Writes are keyed by the owner's id; public reads by a PUBLIC slug only. */
@Repository
public class PortfolioRepository {

    /** One stored portfolio; content and sections are JSON. */
    public record Row(UUID userId, String slug, String displayName, String visibility, String content, String sections,
                      OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime publishedAt) {
    }

    private static final String SELECT = "SELECT user_id, slug, display_name, visibility, content::text, sections::text, "
            + "created_at, updated_at, published_at FROM portfolios ";

    private final JdbcTemplate jdbcTemplate;

    public PortfolioRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Row> find(UUID userId) {
        return jdbcTemplate.query(SELECT + "WHERE user_id = ?", this::row, userId).stream().findFirst();
    }

    /** Only a published profile is ever returned by slug. */
    public Optional<Row> findPublic(String slug) {
        return jdbcTemplate.query(SELECT + "WHERE slug = ? AND visibility = 'PUBLIC'", this::row, slug).stream().findFirst();
    }

    public boolean slugTaken(String slug, UUID exceptUserId) {
        Boolean taken = jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM portfolios WHERE slug = ? AND user_id <> ?)",
                Boolean.class, slug, exceptUserId);
        return Boolean.TRUE.equals(taken);
    }

    public void insert(UUID userId, String slug, String displayName, String content, String sections, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO portfolios (user_id, slug, display_name, content, sections, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, userId, slug, displayName, content, sections, ts(now), ts(now));
    }

    public void update(UUID userId, String displayName, String content, String sections, OffsetDateTime now) {
        jdbcTemplate.update("UPDATE portfolios SET display_name = ?, content = ?::jsonb, sections = ?::jsonb, updated_at = ? "
                + "WHERE user_id = ?", displayName, content, sections, ts(now), userId);
    }

    public void updateSlug(UUID userId, String slug, OffsetDateTime now) {
        jdbcTemplate.update("UPDATE portfolios SET slug = ?, updated_at = ? WHERE user_id = ?", slug, ts(now), userId);
    }

    public void setVisibility(UUID userId, boolean published, OffsetDateTime now) {
        jdbcTemplate.update("UPDATE portfolios SET visibility = ?, published_at = ?, updated_at = ? WHERE user_id = ?",
                published ? "PUBLIC" : "PRIVATE", published ? ts(now) : null, ts(now), userId);
    }

    public boolean delete(UUID userId) {
        return jdbcTemplate.update("DELETE FROM portfolios WHERE user_id = ?", userId) == 1;
    }

    private Row row(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class),
                rs.getObject(9, OffsetDateTime.class));
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }
}
