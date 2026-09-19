package com.jmip.etl.transform;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The deterministic business key used to recognise the same posting twice.
 *
 * <p>Built from normalised title, company, location and posted date. Two things are
 * deliberately left out:
 *
 * <ul>
 *   <li><b>The description</b>, because boards reword it. Keying on description would
 *       make every cross-source duplicate look like a new job.</li>
 *   <li><b>The source URL</b>, for the same reason in reverse: the whole purpose of this
 *       key is to catch one job listed on several boards, where the URLs necessarily
 *       differ. Re-ingesting the identical URL is already blocked by the database's
 *       unique index on {@code (source, source_url)}, so the two mechanisms cover
 *       different cases rather than duplicating each other.</li>
 * </ul>
 */
@Component
public class ContentFingerprint {

    public String compute(String title,
                          String company,
                          String city,
                          String state,
                          String country,
                          LocalDate postedDate) {
        String basis = Stream.of(title, company, city, state, country,
                        postedDate == null ? null : postedDate.toString())
                .map(ContentFingerprint::normalizeComponent)
                .collect(Collectors.joining("|"));
        return sha256Hex(basis);
    }

    /**
     * Case and spacing must not create false distinctions: "Senior  Java Engineer" and
     * "senior java engineer" are the same posting.
     */
    private static String normalizeComponent(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
