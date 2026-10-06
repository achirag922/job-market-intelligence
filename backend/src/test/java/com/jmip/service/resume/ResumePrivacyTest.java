package com.jmip.service.resume;

import com.jmip.config.ResumePrivacyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumePrivacyTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static ResumePrivacyProperties settings(String key, Duration retention) {
        return new ResumePrivacyProperties(key, retention, "0 30 3 * * *");
    }

    @Test
    @DisplayName("retention is off at zero, and otherwise must be at least a day")
    void retentionIsValidated() {
        assertThat(settings("", Duration.ZERO).retentionEnabled()).isFalse();
        assertThat(settings("", Duration.ofDays(365)).retentionEnabled()).isTrue();
        assertThat(settings("", Duration.ofDays(1)).retentionEnabled()).isTrue();

        assertThatThrownBy(() -> settings("", Duration.ofSeconds(30))).hasMessageContaining("at least 1 day");
        assertThatThrownBy(() -> settings("", Duration.ofHours(23))).hasMessageContaining("at least 1 day");
        assertThatThrownBy(() -> settings("", Duration.ofDays(-1))).hasMessageContaining("negative");
    }

    @Test
    @DisplayName("the key never appears in the settings' string form")
    void keyIsMasked() {
        assertThat(settings(KEY, Duration.ZERO).toString()).doesNotContain(KEY).contains("encryptionKey=****");
    }

    @Test
    @DisplayName("a malformed or wrongly sized key stops startup")
    void keyIsValidated() {
        assertThatThrownBy(() -> new ResumeCipher(settings("not base64!", Duration.ZERO))).hasMessageContaining("base64");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new ResumeCipher(settings(shortKey, Duration.ZERO))).hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("files and text round-trip, are never stored in the clear, and differ every time")
    void encryptsAtRest() {
        ResumeCipher cipher = new ResumeCipher(settings(KEY, Duration.ZERO));
        byte[] pdf = "%PDF-1.7 Jane Doe, Java developer".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = cipher.sealFile(pdf);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).startsWith("JMIPENC1").doesNotContain("Jane Doe", "%PDF");
        assertThat(cipher.openFile(sealed)).isEqualTo(pdf);
        assertThat(cipher.sealFile(pdf)).isNotEqualTo(sealed);

        String text = cipher.sealText("Jane Doe, Java developer");
        assertThat(text).startsWith("enc:v1:").doesNotContain("Jane");
        assertThat(cipher.openText(text)).isEqualTo("Jane Doe, Java developer");
    }

    @Test
    @DisplayName("tampered content fails to decrypt, and says nothing about the content")
    void tamperingIsDetected() {
        ResumeCipher cipher = new ResumeCipher(settings(KEY, Duration.ZERO));
        byte[] sealed = cipher.sealFile("%PDF-1.7 secret".getBytes(StandardCharsets.UTF_8));
        sealed[sealed.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.openFile(sealed))
                .hasMessage("Resume content could not be decrypted")
                .hasMessageNotContaining("secret");
    }

    @Test
    @DisplayName("without a key content is stored as is, and content from before encryption still reads")
    void plaintextPassesThrough() {
        ResumeCipher off = new ResumeCipher(settings("", Duration.ZERO));
        assertThat(off.enabled()).isFalse();
        assertThat(off.sealText("plain")).isEqualTo("plain");

        ResumeCipher on = new ResumeCipher(settings(KEY, Duration.ZERO));
        assertThat(on.openText("written before encryption")).isEqualTo("written before encryption");
        assertThat(on.openFile("%PDF-1.7".getBytes(StandardCharsets.UTF_8))).asString().isEqualTo("%PDF-1.7");
    }
}
