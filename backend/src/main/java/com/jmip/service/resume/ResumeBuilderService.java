package com.jmip.service.resume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.resume.BuilderContent;
import com.jmip.dto.resume.BuilderResumeRequest;
import com.jmip.dto.resume.BuilderResumeResponse;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.entity.User;
import com.jmip.repository.ResumeRepository;
import com.jmip.repository.UserRepository;
import com.jmip.service.auth.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * V9.4: the resume builder. A built resume is an ordinary resume (versions, default, deletion,
 * analysis, optimisation and matching all apply) whose sections the owner writes here. On every
 * save its text is regenerated from those sections and its skills extracted from that text by the
 * same matcher uploads use. Nothing is ever added to what the user wrote. Ownership is checked by
 * {@link ResumeService} for every resume; content is never logged.
 */
@Service
public class ResumeBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ResumeBuilderService.class);

    private final ResumeRepository repository;
    private final ResumeService resumeService;
    private final ResumeSkillMatcher skillMatcher;
    private final ResumePdfRenderer pdfRenderer;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ResumeBuilderService(ResumeRepository repository, ResumeService resumeService, ResumeSkillMatcher skillMatcher,
                                ResumePdfRenderer pdfRenderer, UserRepository users, CurrentUser currentUser,
                                ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.resumeService = resumeService;
        this.skillMatcher = skillMatcher;
        this.pdfRenderer = pdfRenderer;
        this.users = users;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public BuilderResumeResponse create(BuilderResumeRequest request) {
        UUID owner = currentUser.requireId();
        requireRoom(owner);
        BuilderContent content = request.content() != null ? request.content()
                : BuilderContent.blank(users.findById(owner).map(User::getFullName).orElse(null));
        String title = request.title() == null || request.title().isBlank() ? "Resume" : request.title().strip();
        Resume resume = Resume.built(UUID.randomUUID(), owner, title, json(content), now());
        resume.describe(title, blankToNull(request.versionLabel()), now());
        if (!repository.existsByUserIdAndDefaultResumeTrue(owner)) {
            resume.setDefault(true, now());
        }
        process(resume, content);
        repository.save(resume);
        log.info("Built resume {} created", resume.getId());
        return response(resume, content);
    }

    @Transactional(readOnly = true)
    public BuilderResumeResponse get(UUID id) {
        Resume resume = requireBuilt(id);
        return response(resume, content(resume));
    }

    @Transactional
    public BuilderResumeResponse save(UUID id, BuilderContent content) {
        Resume resume = requireBuilt(id);
        resume.replaceBuilderContent(json(content), now());
        process(resume, content);
        log.info("Built resume {} saved", id);
        return response(resume, content);
    }

    /** A copy to tailor: same sections, a new name, never the default. */
    @Transactional
    public BuilderResumeResponse duplicate(UUID id) {
        Resume source = requireBuilt(id);
        UUID owner = currentUser.requireId();
        requireRoom(owner);
        BuilderContent content = content(source);
        String title = ("Copy of " + source.getTitle());
        Resume copy = Resume.built(UUID.randomUUID(), owner, title.length() > 100 ? title.substring(0, 100) : title,
                json(content), now());
        copy.describe(copy.getTitle(), source.getVersionLabel(), now());
        process(copy, content);
        repository.save(copy);
        log.info("Built resume {} duplicated as {}", id, copy.getId());
        return response(copy, content);
    }

    /** The PDF in the resume's template, and a download name from its title. */
    @Transactional(readOnly = true)
    public Export export(UUID id) {
        Resume resume = requireBuilt(id);
        String name = resume.getTitle().replaceAll("[^A-Za-z0-9 _-]", "").strip().replace(' ', '-');
        return new Export(pdfRenderer.render(content(resume)), (name.isEmpty() ? "resume" : name) + ".pdf");
    }

    public record Export(byte[] pdf, String fileName) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Export that && java.util.Arrays.equals(pdf, that.pdf)
                    && java.util.Objects.equals(fileName, that.fileName);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(pdf) + java.util.Objects.hashCode(fileName);
        }

        /** The size only: a PDF's bytes are never worth printing. */
        @Override
        public String toString() {
            return "Export[fileName=" + fileName + ", pdf=" + (pdf == null ? 0 : pdf.length) + " bytes]";
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Owner-checked by {@link ResumeService}; an uploaded resume cannot be edited here. */
    private Resume requireBuilt(UUID id) {
        Resume resume = resumeService.requireCompletedResume(id);
        if (!resume.isBuilt()) {
            throw new InvalidRequestException("This resume was uploaded as a file; build a new one to edit it here");
        }
        return resume;
    }

    private void requireRoom(UUID owner) {
        if (repository.countByUserId(owner) >= ResumeService.MAX_RESUMES_PER_ACCOUNT) {
            throw new InvalidRequestException("You can keep at most " + ResumeService.MAX_RESUMES_PER_ACCOUNT
                    + " resumes; delete one to create another");
        }
    }

    /** The text analysis reads, and the skills in it, from the sections alone. */
    private void process(Resume resume, BuilderContent content) {
        String text = ResumeDocument.plainText(content);
        Set<Skill> skills = skillMatcher.match(text);
        resume.markCompleted(text, skills, now());
    }

    private BuilderResumeResponse response(Resume resume, BuilderContent content) {
        return new BuilderResumeResponse(resumeService.toResponse(resume), content);
    }

    private BuilderContent content(Resume resume) {
        try {
            return objectMapper.readValue(resume.getBuilderContent(), BuilderContent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored resume content is unreadable", exception);
        }
    }

    private String json(BuilderContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Resume content cannot be stored", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
