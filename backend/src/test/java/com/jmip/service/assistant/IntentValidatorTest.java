package com.jmip.service.assistant;

import com.jmip.config.AssistantProperties;
import com.jmip.dto.assistant.AssistantEntities;
import com.jmip.dto.assistant.AssistantIntent;
import com.jmip.dto.assistant.ConversationContext;
import com.jmip.entity.Company;
import com.jmip.entity.Location;
import com.jmip.entity.Skill;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.CompanyRepository;
import com.jmip.repository.LocationRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.repository.projection.CategoryCountRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The gate between what a model proposed and what the application will do.
 *
 * <p>These tests are the security argument written down. Everything the model can influence
 * is tried here in its hostile form — an intent that is not in the enum, a skill nobody has,
 * a limit of a million, SQL where a name should be — and in each case the expected outcome
 * is a refusal, not a query.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntentValidatorTest {

    @Mock
    private SkillRepository skillRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private AnalyticsRepository analyticsRepository;

    private IntentValidator validator;

    @BeforeEach
    void setUp() {
        // Built before any stubbing starts. Creating a stubbed mock inside an in-progress
        // when(...) nests one stubbing inside another, which Mockito rejects.
        Optional<Skill> java = Optional.of(skill("Java"));
        Optional<Skill> kubernetes = Optional.of(skill("Kubernetes"));
        Optional<Company> acme = Optional.of(company(7L, "Acme Systems"));
        List<Location> bengaluru = List.of(location("Bengaluru", "Karnataka", "India"));

        // A small dataset: two skills, one company, one place, two categories.
        lenient().when(skillRepository.findFirstByNameIgnoreCase(anyString()))
                .thenReturn(Optional.empty());
        lenient().when(skillRepository.findFirstByNameIgnoreCase("Java")).thenReturn(java);
        lenient().when(skillRepository.findFirstByNameIgnoreCase("kubernetes")).thenReturn(kubernetes);

        lenient().when(companyRepository.findFirstByNameIgnoreCase(anyString()))
                .thenReturn(Optional.empty());
        lenient().when(companyRepository.findFirstByNameIgnoreCase("Acme Systems")).thenReturn(acme);

        lenient().when(locationRepository.findByPlaceName(anyString())).thenReturn(List.of());
        lenient().when(locationRepository.findByPlaceName("bengaluru")).thenReturn(bengaluru);

        lenient().when(analyticsRepository.countByCategory()).thenReturn(List.of(
                new CategoryCountRow("Backend Developer", 19),
                new CategoryCountRow("Data Engineer", 15)));

        EntityResolver resolver = new EntityResolver(
                skillRepository, companyRepository, locationRepository, analyticsRepository);
        validator = new IntentValidator(resolver, new AssistantProperties(10, 25, 20, 5, 500));
    }

    // ------------------------------------------------------------ intent names

    @Test
    @DisplayName("a supported intent with resolvable entities is accepted")
    void acceptsValidIntent() {
        IntentValidation result = validate("SKILL_DEMAND", entities("jobCategory", "backend developer"));

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.intent().intent()).isEqualTo(AssistantIntent.SKILL_DEMAND);
        // Canonicalised to the stored spelling, not the spelling in the question.
        assertThat(result.intent().entities().jobCategory()).isEqualTo("Backend Developer");
    }

    @Test
    @DisplayName("an intent name outside the enum is refused")
    void rejectsUnknownIntent() {
        IntentValidation result = validate("EXPORT_ALL_DATA", AssistantEntities.empty());

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("rephrase");
    }

    @Test
    @DisplayName("an intent of SQL is refused like any other unknown name")
    void rejectsSqlAsIntent() {
        // There is no branch that could run this, but the point of the enum is that the
        // question of "what if the model returns SQL" has one answer everywhere.
        IntentValidation result = validate("SELECT * FROM jobs", AssistantEntities.empty());

        assertThat(result.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("UNSUPPORTED is a refusal, not something to route")
    void rejectsUnsupported() {
        assertThat(validate("UNSUPPORTED", AssistantEntities.empty()).isAccepted()).isFalse();
    }

    @Test
    @DisplayName("a null intent is refused")
    void rejectsNullIntent() {
        assertThat(validate(null, AssistantEntities.empty()).isAccepted()).isFalse();
    }

    // --------------------------------------------------------------- entities

    @Test
    @DisplayName("a skill that does not exist is refused, and named in the message")
    void rejectsUnknownSkill() {
        IntentValidation result = validate("SKILL_TREND", entities("skill", "Cobol"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("skill").contains("Cobol");
    }

    @Test
    @DisplayName("a category that does not exist is refused")
    void rejectsUnknownCategory() {
        IntentValidation result = validate("SKILL_DEMAND", entities("jobCategory", "Astronaut"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("job category").contains("Astronaut");
    }

    @Test
    @DisplayName("a company that does not exist is refused")
    void rejectsUnknownCompany() {
        IntentValidation result = validate("COMPANY_DEMAND", entities("company", "Initech"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("company");
    }

    @Test
    @DisplayName("a location that does not exist is refused")
    void rejectsUnknownLocation() {
        IntentValidation result = validate("LOCATION_DEMAND", entities("location", "Atlantis"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("location");
    }

    @Test
    @DisplayName("a skill name carrying SQL resolves to nothing and is refused")
    void rejectsSqlAsEntity() {
        // It is refused for the ordinary reason — no skill has that name. The dictionary
        // lookup is what makes injection irrelevant here, not a filter on the string.
        IntentValidation result = validate("SKILL_TREND",
                entities("skill", "Java'; DROP TABLE jobs; --"));

        assertThat(result.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("entity names are canonicalised to the database's own spelling")
    void canonicalisesEntities() {
        IntentValidation result = validate("SKILL_TREND", entities("skill", "kubernetes"));

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.intent().entities().skill()).isEqualTo("Kubernetes");
    }

    @Test
    @DisplayName("a resolved company carries its id, not just its name")
    void resolvesCompanyId() {
        IntentValidation result = validate("SKILL_DEMAND", entities("company", "Acme Systems"));

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.intent().entities().companyId()).isEqualTo(7L);
    }

    // ------------------------------------------------------- required entities

    @Test
    @DisplayName("a trend with no skill asks which skill rather than trending everything")
    void requiresSkillForTrend() {
        IntentValidation result = validate("SKILL_TREND", AssistantEntities.empty());

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("Which skill");
    }

    @Test
    @DisplayName("a comparison with only one side asks for the other")
    void requiresBothSidesOfComparison() {
        IntentValidation result = validate("SKILL_COMPARISON", entities("skill", "Java"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("two skills");
    }

    @Test
    @DisplayName("a category comparison needs both categories")
    void requiresBothCategories() {
        IntentValidation result = validate("CATEGORY_COMPARISON",
                entities("jobCategory", "Backend Developer"));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("two job categories");
    }

    // ------------------------------------------------------------------ limits

    @Test
    @DisplayName("an absurd limit is clamped to the configured maximum")
    void clampsLargeLimit() {
        IntentValidation result = validate("SKILL_DEMAND", AssistantEntities.empty(), 1_000_000, null);

        assertThat(result.intent().limit()).isEqualTo(25);
    }

    @Test
    @DisplayName("a missing limit becomes the default")
    void defaultsMissingLimit() {
        assertThat(validate("SKILL_DEMAND", AssistantEntities.empty()).intent().limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("a negative or zero limit becomes the default rather than an empty result")
    void rejectsNonPositiveLimit() {
        assertThat(validate("SKILL_DEMAND", AssistantEntities.empty(), -5, null).intent().limit())
                .isEqualTo(10);
        assertThat(validate("SKILL_DEMAND", AssistantEntities.empty(), 0, null).intent().limit())
                .isEqualTo(10);
    }

    @Test
    @DisplayName("job searches are capped lower than analytics, being heavier rows")
    void clampsJobSearchSeparately() {
        assertThat(validate("JOB_SEARCH", AssistantEntities.empty(), 500, null).intent().limit())
                .isEqualTo(20);
    }

    // --------------------------------------------------------------- time range

    @Test
    @DisplayName("a period is clamped to the window the trend service accepts")
    void clampsTimeRange() {
        assertThat(validate("SKILL_TREND", entities("skill", "Java"), null, "999").intent().months())
                .isEqualTo(36);
        assertThat(validate("SKILL_TREND", entities("skill", "Java"), null, "1").intent().months())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an unparseable period is dropped rather than failing the question")
    void ignoresUnparseableTimeRange() {
        IntentValidation result = validate("SKILL_TREND", entities("skill", "Java"), null, "last year-ish");

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.intent().months()).isNull();
    }

    // ------------------------------------------------------------ conversation

    @Test
    @DisplayName("an entity the new question omits is carried over from the previous turn")
    void carriesForwardOmittedEntities() {
        // "Top skills for Java developers" then "what about Bengaluru?" — the category has
        // to survive, or the follow-up silently answers a much broader question.
        ConversationContext context = new ConversationContext(
                "top skills for backend developers",
                AssistantIntent.SKILL_DEMAND,
                new AssistantEntities(null, null, "Backend Developer", null, null, null, null));

        IntentValidation result = validator.validate(
                new IntentExtraction("SKILL_DEMAND", entities("location", "bengaluru"), null, null),
                context);

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.intent().entities().jobCategory()).isEqualTo("Backend Developer");
        assertThat(result.intent().entities().location()).isEqualTo("Bengaluru");
    }

    @Test
    @DisplayName("a new value for an entity replaces the remembered one")
    void newValueOverridesContext() {
        ConversationContext context = new ConversationContext(
                "backend skills", AssistantIntent.SKILL_DEMAND,
                new AssistantEntities(null, null, "Backend Developer", null, null, null, null));

        IntentValidation result = validator.validate(
                new IntentExtraction("SKILL_DEMAND", entities("jobCategory", "Data Engineer"), null, null),
                context);

        assertThat(result.intent().entities().jobCategory()).isEqualTo("Data Engineer");
    }

    @Test
    @DisplayName("comparison operands are never carried forward")
    void doesNotCarryComparisonOperands() {
        // Remembering half a comparison would silently change what is being compared.
        ConversationContext context = new ConversationContext(
                "compare java and kubernetes", AssistantIntent.SKILL_COMPARISON,
                new AssistantEntities("Java", "Kubernetes", null, null, null, null, null));

        IntentValidation result = validator.validate(
                new IntentExtraction("SKILL_COMPARISON", entities("skill", "Java"), null, null),
                context);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.rejection()).contains("two skills");
    }

    @Test
    @DisplayName("a remembered entity that no longer resolves is refused, not ignored")
    void rejectsStaleContextEntity() {
        ConversationContext context = new ConversationContext(
                "skills for astronauts", AssistantIntent.SKILL_DEMAND,
                new AssistantEntities(null, null, "Astronaut", null, null, null, null));

        IntentValidation result = validator.validate(
                new IntentExtraction("SKILL_DEMAND", AssistantEntities.empty(), null, null), context);

        assertThat(result.isAccepted()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private IntentValidation validate(String intent, AssistantEntities entities) {
        return validate(intent, entities, null, null);
    }

    private IntentValidation validate(String intent, AssistantEntities entities,
                                      Integer limit, String timeRange) {
        return validator.validate(
                new IntentExtraction(intent, entities, timeRange, limit), ConversationContext.empty());
    }

    private static AssistantEntities entities(String field, String value) {
        return switch (field) {
            case "skill" -> new AssistantEntities(value, null, null, null, null, null, null);
            case "jobCategory" -> new AssistantEntities(null, null, value, null, null, null, null);
            case "company" -> new AssistantEntities(null, null, null, null, value, null, null);
            case "location" -> new AssistantEntities(null, null, null, null, null, value, null);
            default -> throw new IllegalArgumentException(field);
        };
    }

    // The entities are immutable outside persistence — getters only, protected
    // constructors — so a stub is the way to hand the resolver a known row.

    private static Skill skill(String name) {
        Skill skill = org.mockito.Mockito.mock(Skill.class);
        lenient().when(skill.getName()).thenReturn(name);
        return skill;
    }

    private static Company company(Long id, String name) {
        Company company = org.mockito.Mockito.mock(Company.class);
        lenient().when(company.getId()).thenReturn(id);
        lenient().when(company.getName()).thenReturn(name);
        return company;
    }

    private static Location location(String city, String state, String country) {
        Location location = org.mockito.Mockito.mock(Location.class);
        lenient().when(location.getCity()).thenReturn(city);
        lenient().when(location.getState()).thenReturn(state);
        lenient().when(location.getCountry()).thenReturn(country);
        return location;
    }
}
