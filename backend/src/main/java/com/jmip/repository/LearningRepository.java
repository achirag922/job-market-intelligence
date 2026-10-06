package com.jmip.repository;

import com.jmip.dto.learning.LearningDtos.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** V9.5: learning items and resources. Every read and write is scoped to the owner's id. */
@Repository
public class LearningRepository {

    /** One stored item, as the service needs it. */
    public record ItemRow(UUID id, UUID goalId, Long skillId, String skillName, String topic, String priority, String status,
                          int progress, LocalDate targetDate, String notes, OffsetDateTime createdAt,
                          OffsetDateTime updatedAt, OffsetDateTime startedAt, OffsetDateTime completedAt) {
    }

    private static final String ITEM_COLUMNS = """
            id, goal_id, skill_id, skill_name, topic, priority, status, progress, target_date, notes,
            created_at, updated_at, started_at, completed_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public LearningRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertItem(UUID id, UUID userId, UUID goalId, Long skillId, String skillName, String topic, String priority,
                           LocalDate targetDate, String notes, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO learning_items (id, user_id, goal_id, skill_id, skill_name, topic, priority, target_date, notes,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, goalId, skillId, skillName, topic, priority, date(targetDate), notes, ts(now), ts(now));
    }

    public long countItems(UUID userId) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM learning_items WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public List<ItemRow> items(UUID userId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM learning_items WHERE user_id = ? "
                        + "ORDER BY CASE priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, target_date NULLS LAST, created_at",
                this::item, userId);
    }

    public Optional<ItemRow> item(UUID id, UUID userId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM learning_items WHERE id = ? AND user_id = ?",
                this::item, id, userId).stream().findFirst();
    }

    public void updateItem(UUID id, UUID userId, String topic, String priority, int progress, LocalDate targetDate,
                           String notes, OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE learning_items SET topic = ?, priority = ?, progress = ?, target_date = ?, notes = ?, updated_at = ?
                 WHERE id = ? AND user_id = ?
                """, topic, priority, progress, date(targetDate), notes, ts(now), id, userId);
    }

    /** Starting records when; completing sets progress to 100 and when; reopening clears the completion. */
    public void updateStatus(UUID id, UUID userId, String status, OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE learning_items
                   SET status = ?,
                       progress = CASE WHEN ? = 'COMPLETED' THEN 100 ELSE progress END,
                       started_at = CASE WHEN ? <> 'NOT_STARTED' THEN coalesce(started_at, ?::timestamptz) ELSE started_at END,
                       completed_at = CASE WHEN ? = 'COMPLETED' THEN ?::timestamptz END,
                       updated_at = ?
                 WHERE id = ? AND user_id = ?
                """, status, status, status, ts(now), status, ts(now), ts(now), id, userId);
    }

    public boolean deleteItem(UUID id, UUID userId) {
        return jdbcTemplate.update("DELETE FROM learning_items WHERE id = ? AND user_id = ?", id, userId) == 1;
    }

    public long countResources(UUID itemId) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM learning_resources WHERE item_id = ?", Long.class, itemId);
        return count == null ? 0 : count;
    }

    public void insertResource(UUID id, UUID itemId, UUID userId, String title, String url, String type, String notes,
                               OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO learning_resources (id, item_id, user_id, title, url, type, notes, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, itemId, userId, title, url, type, notes, ts(now));
    }

    /** Resources with the item they belong to, for grouping. */
    public List<ResourceRow> resourceRows(UUID userId) {
        return jdbcTemplate.query("""
                SELECT id, item_id, title, url, type, notes, created_at FROM learning_resources
                 WHERE user_id = ? ORDER BY created_at, id
                """, (rs, row) -> new ResourceRow(rs.getObject(2, UUID.class), resource(rs)), userId);
    }

    public record ResourceRow(UUID itemId, Resource resource) {
    }

    public boolean updateResource(UUID id, UUID userId, String title, String url, String type, String notes) {
        return jdbcTemplate.update("UPDATE learning_resources SET title = ?, url = ?, type = ?, notes = ? "
                + "WHERE id = ? AND user_id = ?", title, url, type, notes, id, userId) == 1;
    }

    public boolean deleteResource(UUID id, UUID userId) {
        return jdbcTemplate.update("DELETE FROM learning_resources WHERE id = ? AND user_id = ?", id, userId) == 1;
    }

    public Optional<Resource> resource(UUID id, UUID userId) {
        return jdbcTemplate.query("SELECT id, item_id, title, url, type, notes, created_at FROM learning_resources "
                + "WHERE id = ? AND user_id = ?", (rs, row) -> resource(rs), id, userId).stream().findFirst();
    }

    private ItemRow item(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ItemRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, Long.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                rs.getObject(9, LocalDate.class), rs.getString(10), rs.getObject(11, OffsetDateTime.class),
                rs.getObject(12, OffsetDateTime.class), rs.getObject(13, OffsetDateTime.class),
                rs.getObject(14, OffsetDateTime.class));
    }

    private static Resource resource(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Resource(rs.getObject(1, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class));
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }

    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }
}
