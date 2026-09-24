package com.jmip.service.resume;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.config.ResumeStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Validates uploads and writes them to disk.
 *
 * <p>The stored name is derived from the resume's generated id, never from the uploaded
 * filename. That is the whole defence against path traversal: a file called
 * {@code ../../etc/passwd} can never influence where anything is written, because its name
 * is not used to build a path at all. The original name is kept in the database purely to
 * show back to the user.
 */
@Service
public class ResumeStorageService {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorageService.class);

    private static final String STORED_FILE_EXTENSION = ".pdf";
    /** Every PDF begins with "%PDF-". */
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};

    private final ResumeStorageProperties properties;
    private final Path storageDirectory;

    public ResumeStorageService(ResumeStorageProperties properties) {
        this.properties = properties;
        this.storageDirectory = Path.of(properties.directory()).toAbsolutePath().normalize();
    }

    /**
     * Rejects anything that should not be stored, with a message naming the actual
     * problem rather than a generic failure.
     */
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("The uploaded file is empty");
        }
        if (file.getSize() > properties.maxFileSizeBytes()) {
            throw new InvalidRequestException(
                    "The file is %d bytes, which exceeds the %d byte limit"
                            .formatted(file.getSize(), properties.maxFileSizeBytes()));
        }
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT).split(";")[0].strip();
        if (!properties.allowedContentTypes().contains(contentType)) {
            throw new InvalidRequestException(
                    "Only %s files are accepted, but the upload was '%s'"
                            .formatted(String.join(", ", properties.allowedContentTypes()),
                                    contentType.isEmpty() ? "unknown" : contentType));
        }
        // The declared type is whatever the client says. The first bytes are what the file
        // is: anything that does not start like a PDF — an executable renamed to .pdf,
        // an HTML page, a script — is refused before it is stored or parsed.
        if (!startsWithPdfSignature(file)) {
            throw new InvalidRequestException("The file is not a PDF document");
        }
    }

    private static boolean startsWithPdfSignature(MultipartFile file) {
        try (var in = file.getInputStream()) {
            byte[] head = in.readNBytes(PDF_SIGNATURE.length);
            return java.util.Arrays.equals(head, PDF_SIGNATURE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }

    /**
     * @param resumeId identifies the resume and names the file, so the two cannot drift
     * @return the stored file name, which is all the database needs to find it again
     */
    public String store(UUID resumeId, byte[] content) {
        String storedFileName = resumeId + STORED_FILE_EXTENSION;
        try {
            Files.createDirectories(storageDirectory);
            Path target = storageDirectory.resolve(storedFileName).normalize();
            // Belt and braces: the name is generated, but a path that escaped the
            // configured directory must never be written to regardless.
            if (!target.startsWith(storageDirectory)) {
                throw new IllegalStateException("Refusing to write outside the resume storage directory");
            }
            Files.write(target, content);
            log.info("Stored resume {} ({} bytes)", resumeId, content.length);
            return storedFileName;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded resume", e);
        }
    }

    /** Used when processing fails, so a rejected upload does not linger on disk. */
    public void deleteQuietly(String storedFileName) {
        try {
            Files.deleteIfExists(storageDirectory.resolve(storedFileName).normalize());
        } catch (IOException e) {
            log.warn("Could not delete stored resume file {}", storedFileName, e);
        }
    }

    /** Package private: exposed for tests and logging, never through the API. */
    Path storageDirectory() {
        return storageDirectory;
    }
}
