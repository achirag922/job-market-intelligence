package com.jmip.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the .trivyignore entry for CVE-2026-47884 (spring-webmvc XsltView): the suppression is only
 * valid while JMIP renders no XSLT views. Adding XsltView, an XSLT view resolver or an .xsl/.xslt
 * resource fails this test, so the accepted risk cannot silently become reachable.
 */
class NoXsltViewTest {

    private static final Path MAIN = Path.of("src/main");

    @Test
    @DisplayName("no XsltView, XSLT view resolver or XSL stylesheet in the backend")
    void noXslt() throws IOException {
        assertThat(MAIN).isDirectory();
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<String> offending = files.filter(Files::isRegularFile)
                    .filter(NoXsltViewTest::usesXslt)
                    .map(Path::toString)
                    .toList();
            assertThat(offending).as("XSLT use makes CVE-2026-47884 reachable; remove the .trivyignore entry and upgrade").isEmpty();
        }
    }

    private static boolean usesXslt(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xsl") || name.endsWith(".xslt")) {
            return true;
        }
        if (!name.endsWith(".java") && !name.endsWith(".yml") && !name.endsWith(".properties")) {
            return false;
        }
        try {
            String text = Files.readString(file);
            return text.contains("XsltView") || text.contains("XsltViewResolver");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }
}
