package com.jmip.service.assistant;

import com.jmip.dto.assistant.AssistantEntities;
import com.jmip.entity.Company;
import com.jmip.entity.Location;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.CompanyRepository;
import com.jmip.repository.LocationRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.repository.projection.CategoryCountRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns names out of a question into values the database recognises.
 *
 * <p>This is where untrusted text stops being untrusted. A skill is not a skill because a
 * model said so; it is a skill because a row in {@code skills} has that name. Anything that
 * does not resolve comes back as a failure, and the caller tells the user it could not be
 * found — which is a better answer than silently querying for something else.
 *
 * <p>The exception is {@code title}, which is free text by nature and has no dictionary to
 * check against. It is length-bounded and stripped of control characters here, and reaches
 * the database only as a bound parameter of an existing named query, never as assembled
 * SQL. No amount of filtering would make string-built SQL safe, and none is needed when the
 * query is parameterised.
 */
@Component
@Transactional(readOnly = true)
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    /** Long enough for any real job title, short enough that a pasted page is refused. */
    private static final int MAX_TITLE_LENGTH = 100;

    private final SkillRepository skillRepository;
    private final CompanyRepository companyRepository;
    private final LocationRepository locationRepository;
    private final AnalyticsRepository analyticsRepository;

    public EntityResolver(SkillRepository skillRepository,
                          CompanyRepository companyRepository,
                          LocationRepository locationRepository,
                          AnalyticsRepository analyticsRepository) {
        this.skillRepository = skillRepository;
        this.companyRepository = companyRepository;
        this.locationRepository = locationRepository;
        this.analyticsRepository = analyticsRepository;
    }

    /**
     * Resolves every entity that was named, reporting the first one that does not exist.
     *
     * @return the resolved entities, or a failure naming what could not be found
     */
    public Resolution resolve(AssistantEntities requested) {
        Resolution skill = lookup(requested.skill(), this::findSkill, "skill");
        if (skill.failed()) {
            return skill;
        }
        Resolution secondSkill = lookup(requested.secondSkill(), this::findSkill, "skill");
        if (secondSkill.failed()) {
            return secondSkill;
        }
        Resolution category = lookup(requested.jobCategory(), this::findCategory, "job category");
        if (category.failed()) {
            return category;
        }
        Resolution secondCategory =
                lookup(requested.secondJobCategory(), this::findCategory, "job category");
        if (secondCategory.failed()) {
            return secondCategory;
        }
        Resolution location = lookup(requested.location(), this::findLocation, "location");
        if (location.failed()) {
            return location;
        }

        Optional<Company> company = Optional.empty();
        if (named(requested.company())) {
            company = companyRepository.findFirstByNameIgnoreCase(requested.company());
            if (company.isEmpty()) {
                return Resolution.notFound("company", requested.company());
            }
        }

        return Resolution.of(new ResolvedEntities(
                skill.value(),
                secondSkill.value(),
                category.value(),
                secondCategory.value(),
                company.map(Company::getId).orElse(null),
                company.map(Company::getName).orElse(null),
                location.value(),
                sanitiseTitle(requested.title())));
    }

    /**
     * Looks one name up, treating "not named" and "named but unknown" as different
     * outcomes. The first is fine; the second has to be reported, because answering
     * without a filter the user asked for would answer a different question.
     */
    private Resolution lookup(String value, Function<String, Optional<String>> finder, String kind) {
        if (!named(value)) {
            return Resolution.absent();
        }
        Optional<String> found = finder.apply(value);
        if (found.isEmpty()) {
            log.debug("Assistant could not resolve {}", kind);
            return Resolution.notFound(kind, value);
        }
        return Resolution.resolved(found.get());
    }

    /** The canonical name of a stored skill, or empty when nothing has that name. */
    private Optional<String> findSkill(String name) {
        return skillRepository.findFirstByNameIgnoreCase(name).map(skill -> skill.getName());
    }

    /**
     * The canonical name of a category currently in use.
     *
     * <p>Read from the postings rather than from a list in code. The categories are ETL
     * configuration and they change; a hardcoded copy here would drift, and a question
     * about a real category would be refused because this file was not updated.
     */
    private Optional<String> findCategory(String name) {
        return analyticsRepository.countByCategory().stream()
                .map(CategoryCountRow::category)
                .filter(stored -> stored.equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * A place name, if any location has it as a city, state or country.
     *
     * <p>The stored spelling is returned, so "bengaluru" becomes "Bengaluru" and the answer
     * echoes the dataset's own wording.
     */
    private Optional<String> findLocation(String name) {
        List<Location> matches = locationRepository.findByPlaceName(name);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matchingPart(matches.get(0), name));
    }

    private static String matchingPart(Location location, String name) {
        if (name.equalsIgnoreCase(location.getCity())) {
            return location.getCity();
        }
        if (name.equalsIgnoreCase(location.getState())) {
            return location.getState();
        }
        return location.getCountry();
    }

    private static boolean named(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Free text, bounded and stripped of control characters.
     *
     * <p>Not an injection defence — the query it feeds is parameterised, which is the
     * actual defence. This stops a title carrying newlines into a log line or a prompt, and
     * keeps its length sane.
     */
    private static String sanitiseTitle(String title) {
        if (!named(title)) {
            return null;
        }
        String cleaned = title.replaceAll("\\p{Cntrl}", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_TITLE_LENGTH ? cleaned.substring(0, MAX_TITLE_LENGTH) : cleaned;
    }

    /**
     * The outcome of resolving one name, or of resolving a whole set.
     *
     * @param value        the canonical value, null when nothing was named or lookup failed
     * @param entities     the full resolved set, only populated by {@link #of}
     * @param unknownKind  what sort of thing was not found — "skill", "company"
     * @param unknownValue the name as the question gave it, echoed back so the user can see
     *                     what was searched for
     */
    public record Resolution(String value, ResolvedEntities entities,
                             String unknownKind, String unknownValue) {

        static Resolution absent() {
            return new Resolution(null, null, null, null);
        }

        static Resolution resolved(String value) {
            return new Resolution(value, null, null, null);
        }

        static Resolution of(ResolvedEntities entities) {
            return new Resolution(null, entities, null, null);
        }

        static Resolution notFound(String kind, String value) {
            return new Resolution(null, null, kind, value);
        }

        public boolean failed() {
            return unknownKind != null;
        }

        public boolean isResolved() {
            return entities != null;
        }

        /** A sentence naming what was not found, for the user. */
        public String message() {
            return "I could not find the " + unknownKind + " \"" + unknownValue
                    + "\" in this dataset.";
        }
    }
}
