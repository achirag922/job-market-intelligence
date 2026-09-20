package com.jmip.service.analytics;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.TitleCountResponse;
import com.jmip.dto.analytics.TitleDemandResponse;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.projection.TitleCountRow;
import com.jmip.repository.projection.TitleSkillCountRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Job title analytics.
 *
 * <p>Titles are grouped by {@link JobTitleNormalizer} in Java rather than in SQL. Distinct
 * titles are a small fraction of postings — hundreds against however many jobs — so the
 * whole set is read and folded in memory. That keeps the grouping rules explicit and unit
 * testable instead of buried in a {@code regexp_replace}. If the title cardinality ever
 * approaches the posting count, this is the part to move into the database.
 */
@Service
@Transactional(readOnly = true)
public class TitleAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(TitleAnalyticsService.class);

    private static final int TOP_SKILLS_PER_TITLE = 5;

    private final AnalyticsRepository analyticsRepository;
    private final JobTitleNormalizer titleNormalizer;

    public TitleAnalyticsService(AnalyticsRepository analyticsRepository, JobTitleNormalizer titleNormalizer) {
        this.analyticsRepository = analyticsRepository;
        this.titleNormalizer = titleNormalizer;
    }

    public PagedResponse<TitleDemandResponse> titleDemand(int page, int size) {
        List<TitleCountRow> rawTitles = analyticsRepository.countByTitle();
        long totalJobs = rawTitles.stream().mapToLong(TitleCountRow::jobCount).sum();

        Map<String, TitleGroup> groups = new LinkedHashMap<>();
        for (TitleCountRow row : rawTitles) {
            String normalized = titleNormalizer.normalize(row.title());
            groups.computeIfAbsent(normalized, TitleGroup::new).add(row.title(), row.jobCount());
        }
        log.debug("Folded {} stored titles into {} normalised groups", rawTitles.size(), groups.size());

        Map<String, List<TitleDemandResponse.TitleSkill>> skillsByTitle = topSkillsByNormalizedTitle(groups);

        List<TitleGroup> ranked = groups.values().stream()
                .sorted(Comparator.comparingLong(TitleGroup::jobCount).reversed()
                        .thenComparing(TitleGroup::title))
                .toList();

        int from = Math.min(page * size, ranked.size());
        int to = Math.min(from + size, ranked.size());

        List<TitleDemandResponse> content = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            TitleGroup group = ranked.get(index);
            content.add(new TitleDemandResponse(
                    group.title(),
                    List.copyOf(group.variants()),
                    group.jobCount(),
                    Metrics.percentageOf(group.jobCount(), totalJobs),
                    index + 1,
                    skillsByTitle.getOrDefault(group.title(), List.of())));
        }

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) ranked.size() / size);
        return new PagedResponse<>(content, page, size, ranked.size(), totalPages,
                page == 0, to >= ranked.size());
    }

    /** The most common normalised titles in one location, as a share of that location. */
    public List<TitleCountResponse> titlesForLocation(Long locationId, int limit) {
        List<TitleCountRow> rows = analyticsRepository.countTitlesForLocation(locationId);
        long locationJobs = rows.stream().mapToLong(TitleCountRow::jobCount).sum();

        Map<String, Long> byNormalized = new LinkedHashMap<>();
        for (TitleCountRow row : rows) {
            byNormalized.merge(titleNormalizer.normalize(row.title()), row.jobCount(), Long::sum);
        }

        return byNormalized.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new TitleCountResponse(
                        entry.getKey(),
                        entry.getValue(),
                        Metrics.percentageOf(entry.getValue(), locationJobs)))
                .toList();
    }

    /**
     * Skills per normalised title.
     *
     * <p>Counts are summed across the stored titles folded into each group, so a skill on
     * both "Senior Backend Engineer" and "Backend Engineer" is counted once per posting
     * rather than once per title variant.
     */
    private Map<String, List<TitleDemandResponse.TitleSkill>> topSkillsByNormalizedTitle(
            Map<String, TitleGroup> groups) {
        Map<String, Map<Long, SkillTally>> tallies = new LinkedHashMap<>();
        for (TitleSkillCountRow row : analyticsRepository.countSkillsByTitle()) {
            String normalized = titleNormalizer.normalize(row.title());
            tallies.computeIfAbsent(normalized, key -> new LinkedHashMap<>())
                    .computeIfAbsent(row.skillId(),
                            key -> new SkillTally(row.skillId(), row.skillName(), row.category()))
                    .add(row.jobCount());
        }

        Map<String, List<TitleDemandResponse.TitleSkill>> result = new LinkedHashMap<>();
        tallies.forEach((normalized, skills) -> {
            long titleJobs = groups.containsKey(normalized) ? groups.get(normalized).jobCount() : 0;
            result.put(normalized, skills.values().stream()
                    .sorted(Comparator.comparingLong(SkillTally::jobCount).reversed()
                            .thenComparing(SkillTally::name))
                    .limit(TOP_SKILLS_PER_TITLE)
                    .map(tally -> new TitleDemandResponse.TitleSkill(
                            tally.id(), tally.name(), tally.category(), tally.jobCount(),
                            Metrics.percentageOf(tally.jobCount(), titleJobs)))
                    .toList());
        });
        return result;
    }

    /** One normalised title and the stored titles folded into it. */
    private static final class TitleGroup {
        private final String title;
        private final TreeSet<String> variants = new TreeSet<>();
        private long jobCount;

        private TitleGroup(String title) {
            this.title = title;
        }

        private void add(String variant, long count) {
            variants.add(variant);
            jobCount += count;
        }

        private String title() {
            return title;
        }

        private TreeSet<String> variants() {
            return variants;
        }

        private long jobCount() {
            return jobCount;
        }
    }

    private static final class SkillTally {
        private final Long id;
        private final String name;
        private final String category;
        private long jobCount;

        private SkillTally(Long id, String name, String category) {
            this.id = id;
            this.name = name;
            this.category = category;
        }

        private void add(long count) {
            jobCount += count;
        }

        private Long id() {
            return id;
        }

        private String name() {
            return name;
        }

        private String category() {
            return category;
        }

        private long jobCount() {
            return jobCount;
        }
    }
}
