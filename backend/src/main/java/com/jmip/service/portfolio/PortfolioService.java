package com.jmip.service.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jmip.common.exception.ConflictException;
import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.portfolio.PortfolioDtos.Content;
import com.jmip.dto.portfolio.PortfolioDtos.ImportDraft;
import com.jmip.dto.portfolio.PortfolioDtos.Link;
import com.jmip.dto.portfolio.PortfolioDtos.Portfolio;
import com.jmip.dto.portfolio.PortfolioDtos.PublicProfile;
import com.jmip.dto.portfolio.PortfolioDtos.SaveRequest;
import com.jmip.dto.portfolio.PortfolioDtos.Sections;
import com.jmip.dto.resume.BuilderContent;
import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.entity.User;
import com.jmip.repository.CareerGoalRepository;
import com.jmip.repository.PortfolioRepository;
import com.jmip.repository.PortfolioRepository.Row;
import com.jmip.repository.UserRepository;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.learning.LearningService;
import com.jmip.service.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * V9.7: the signed-in user's professional portfolio, and its public read-only view.
 *
 * <p>The owner edits only their own portfolio (keyed by the session's account). A profile is
 * private until published, and the public view shows only the sections the owner made visible,
 * never an email, a phone number, an account id, applications, alerts, interviews or the learning
 * plan. Text is stored as typed, minus control and direction-override characters; the browser
 * renders it as text, never as HTML.
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private static final Pattern SLUG = Pattern.compile(com.jmip.dto.portfolio.PortfolioDtos.SLUG_PATTERN);
    private static final Pattern WEB_ADDRESS = Pattern.compile("(?i)^https?://\\S+$");
    /** Control characters (tabs and new lines kept), zero-width and bidirectional overrides. */
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]|[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Set<String> RESERVED = Set.of("admin", "api", "edit", "jmip", "login", "me", "new", "preview",
            "profile", "settings", "signup");

    private final PortfolioRepository repository;
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final CareerGoalRepository goals;
    private final ResumeService resumeService;
    private final LearningService learning;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PortfolioService(PortfolioRepository repository, CurrentUser currentUser, UserRepository users,
                            CareerGoalRepository goals, ResumeService resumeService, LearningService learning,
                            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.users = users;
        this.goals = goals;
        this.resumeService = resumeService;
        this.learning = learning;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Portfolio get() {
        return portfolio(require(currentUser.requireId()));
    }

    @Transactional
    public Portfolio create(SaveRequest request) {
        UUID owner = currentUser.requireId();
        if (repository.find(owner).isPresent()) {
            throw new ConflictException("You already have a portfolio; edit it instead");
        }
        String displayName = clean(request.displayName());
        String slug = request.slug() != null ? requireFreeSlug(request.slug(), owner) : generateSlug(displayName, owner);
        try {
            repository.insert(owner, slug, displayName, json(content(request.content())), json(sections(request.sections())),
                    now());
        } catch (DuplicateKeyException taken) {
            throw new ConflictException("That profile address is already taken");
        }
        log.info("Portfolio created");
        return get();
    }

    @Transactional
    public Portfolio update(SaveRequest request) {
        UUID owner = currentUser.requireId();
        require(owner);
        repository.update(owner, clean(request.displayName()), json(content(request.content())),
                json(sections(request.sections())), now());
        if (request.slug() != null) {
            changeSlug(request.slug());
        }
        return get();
    }

    @Transactional
    public Portfolio changeSlug(String requested) {
        UUID owner = currentUser.requireId();
        Row row = require(owner);
        if (!requested.equals(row.slug())) {
            try {
                repository.updateSlug(owner, requireFreeSlug(requested, owner), now());
            } catch (DuplicateKeyException taken) {
                throw new ConflictException("That profile address is already taken");
            }
        }
        return get();
    }

    @Transactional
    public Portfolio setPublished(boolean published) {
        UUID owner = currentUser.requireId();
        Row row = require(owner);
        if (published != "PUBLIC".equals(row.visibility())) {
            repository.setVisibility(owner, published, now());
            log.info("Portfolio {}", published ? "published" : "unpublished");
        }
        return get();
    }

    @Transactional
    public void delete() {
        if (!repository.delete(currentUser.requireId())) {
            throw ResourceNotFoundException.of("Portfolio", "yours");
        }
    }

    /** Exactly what the public page would show, published or not. */
    @Transactional(readOnly = true)
    public PublicProfile preview() {
        return publicView(require(currentUser.requireId()));
    }

    /** The public page. An unknown, private or malformed slug is the same 404. */
    @Transactional(readOnly = true)
    public PublicProfile publicProfile(String slug) {
        String normalized = slug == null ? "" : slug.toLowerCase(Locale.ROOT);
        Row row = SLUG.matcher(normalized).matches() ? repository.findPublic(normalized).orElse(null) : null;
        if (row == null) {
            throw new ResourceNotFoundException("Profile not found");
        }
        return publicView(row);
    }

    /**
     * A draft from one of the owner's resumes (the current one when none is given): a built resume
     * brings all its sections, an uploaded one its skills. Nothing is saved.
     */
    @Transactional(readOnly = true)
    public ImportDraft importDraft(UUID resumeId) {
        UUID owner = currentUser.requireId();
        UUID id = resumeId != null ? resumeId : resumeService.currentProcessedResumeId()
                .orElseThrow(() -> new InvalidRequestException("Upload or build a resume first"));
        Resume resume = resumeService.requireCompletedResume(id);
        String accountName = users.findById(owner).map(User::getFullName).orElse(null);
        Content content;
        String displayName = accountName;
        String source;
        if ("BUILDER".equals(resume.getSource()) && resume.getBuilderContent() != null) {
            BuilderContent built = read(resume.getBuilderContent(), BuilderContent.class);
            displayName = built.personal().fullName();
            List<Link> links = built.personal().links().stream().filter(url -> WEB_ADDRESS.matcher(url).matches())
                    .map(url -> new Link(host(url), url)).toList();
            content = new Content(built.personal().headline(), built.summary(), built.skills(), built.experience(),
                    built.education(), built.projects(), built.certifications(), built.achievements(), links);
            source = "BUILDER";
        } else {
            content = new Content(null, null, resume.getSkills().stream().map(Skill::getName).sorted().toList(),
                    null, null, null, null, null, null);
            source = "UPLOAD";
        }
        Set<String> have = content.skills().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> learned;
        try {
            learned = learning.plan().progress().completedSkills().stream()
                    .filter(skill -> !have.contains(skill.toLowerCase(Locale.ROOT))).toList();
        } catch (RuntimeException unavailable) {
            learned = List.of();
        }
        return new ImportDraft(displayName == null || displayName.isBlank() ? "Your Name" : displayName, content, learned,
                source);
    }

    // ------------------------------------------------------------------ views

    private Portfolio portfolio(Row row) {
        return new Portfolio(row.slug(), row.displayName(), row.visibility(), "/profile/" + row.slug(),
                read(row.content(), Content.class), read(row.sections(), Sections.class), row.createdAt(), row.updatedAt(),
                row.publishedAt());
    }

    private PublicProfile publicView(Row row) {
        Content c = read(row.content(), Content.class);
        Sections s = read(row.sections(), Sections.class);
        List<String> careerGoals = s.careerGoals()
                ? goals.findByUserIdAndStatusOrderByUpdatedAtDesc(row.userId(), CareerGoalStatus.ACTIVE).stream()
                .map(CareerGoal::getTargetRole).distinct().toList()
                : null;
        return new PublicProfile(row.displayName(), c.headline(), s.about() ? c.about() : null, shown(s.skills(), c.skills()),
                shown(s.experience(), c.experience()), shown(s.education(), c.education()), shown(s.projects(), c.projects()),
                shown(s.certifications(), c.certifications()), shown(s.achievements(), c.achievements()),
                careerGoals == null || careerGoals.isEmpty() ? null : careerGoals, shown(s.links(), c.links()), row.updatedAt());
    }

    private static <T> List<T> shown(boolean visible, List<T> items) {
        return visible && !items.isEmpty() ? items : null;
    }

    // ------------------------------------------------------------------ validation

    private Content content(Content content) {
        Content cleaned = read(json(sanitize(objectMapper.valueToTree(content))), Content.class);
        for (BuilderContent.Project project : cleaned.projects()) {
            if (project.url() != null && !WEB_ADDRESS.matcher(project.url()).matches()) {
                throw new InvalidRequestException("Project links must be http or https addresses");
            }
        }
        return cleaned;
    }

    private static Sections sections(Sections sections) {
        return sections == null ? Sections.defaults() : sections;
    }

    private String requireFreeSlug(String slug, UUID owner) {
        String normalized = slug.toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(normalized).matches()) {
            throw new InvalidRequestException(com.jmip.dto.portfolio.PortfolioDtos.SLUG_MESSAGE);
        }
        if (RESERVED.contains(normalized)) {
            throw new InvalidRequestException("That profile address is reserved; choose another");
        }
        if (repository.slugTaken(normalized, owner)) {
            throw new ConflictException("That profile address is already taken");
        }
        return normalized;
    }

    /** From the display name: "Ana María Ruiz" becomes ana-maria-ruiz, with a number added when it is taken. */
    private String generateSlug(String displayName, UUID owner) {
        String base = Normalizer.normalize(displayName, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.length() > 44) {
            base = base.substring(0, 44).replaceAll("-+$", "");
        }
        if (base.length() < 3 || RESERVED.contains(base)) {
            base = "profile-" + (base.isEmpty() ? "me" : base);
        }
        for (int n = 1; n < 100; n++) {
            String candidate = n == 1 ? base : base + "-" + n;
            if (!repository.slugTaken(candidate, owner)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    private static String clean(String text) {
        return UNSAFE.matcher(text).replaceAll("").strip();
    }

    private static JsonNode sanitize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            fields.forEachRemaining(entries::add);
            entries.forEach(entry -> object.set(entry.getKey(), sanitize(entry.getValue())));
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, sanitize(array.get(i)));
            }
            return array;
        }
        return node.isTextual() ? TextNode.valueOf(clean(node.asText())) : node;
    }

    private static String host(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? "Link" : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException invalid) {
            return "Link";
        }
    }

    // ------------------------------------------------------------------ helpers

    private Row require(UUID owner) {
        return repository.find(owner).orElseThrow(() -> new ResourceNotFoundException("You have no portfolio yet"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Portfolio could not be written", invalid);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Portfolio could not be read", invalid);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
