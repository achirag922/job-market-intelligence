package com.jmip.service.resume;

import com.jmip.config.ResumePrivacyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Application-level encryption of resume content at rest: the stored PDF and the extracted
 * text. AES-256-GCM, a fresh random IV per value, so the same resume never encrypts the same
 * way twice and any tampering fails to decrypt.
 *
 * <p>Encrypted values are self-identifying — files start with {@link #FILE_MAGIC}, text with
 * {@link #TEXT_PREFIX} — so content written before a key was configured still reads back.
 */
@Component
@EnableConfigurationProperties(ResumePrivacyProperties.class)
public class ResumeCipher {

    private static final Logger log = LoggerFactory.getLogger(ResumeCipher.class);

    static final byte[] FILE_MAGIC = "JMIPENC1".getBytes(StandardCharsets.US_ASCII);
    static final String TEXT_PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ResumeCipher(ResumePrivacyProperties properties) {
        if (properties.encryptionKey().isEmpty()) {
            key = null;
            log.warn("JMIP_RESUME_ENCRYPTION_KEY is not set: resume files and extracted text are stored unencrypted");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(properties.encryptionKey());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("JMIP_RESUME_ENCRYPTION_KEY must be base64 (e.g. `openssl rand -base64 32`)");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("JMIP_RESUME_ENCRYPTION_KEY must decode to 32 bytes (AES-256), got " + raw.length);
        }
        key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
    }

    public boolean enabled() {
        return key != null;
    }

    /** The bytes to write for a resume file: encrypted when a key is configured. */
    public byte[] sealFile(byte[] content) {
        if (!enabled()) {
            return content;
        }
        byte[] sealed = encrypt(content);
        return ByteBuffer.allocate(FILE_MAGIC.length + sealed.length).put(FILE_MAGIC).put(sealed).array();
    }

    /** Reverses {@link #sealFile}; plaintext files from before encryption pass through. */
    public byte[] openFile(byte[] stored) {
        if (stored.length < FILE_MAGIC.length || !Arrays.equals(Arrays.copyOf(stored, FILE_MAGIC.length), FILE_MAGIC)) {
            return stored;
        }
        return decrypt(Arrays.copyOfRange(stored, FILE_MAGIC.length, stored.length));
    }

    public String sealText(String text) {
        if (text == null || !enabled()) {
            return text;
        }
        return TEXT_PREFIX + Base64.getEncoder().encodeToString(encrypt(text.getBytes(StandardCharsets.UTF_8)));
    }

    public String openText(String stored) {
        if (stored == null || !stored.startsWith(TEXT_PREFIX)) {
            return stored;
        }
        return new String(decrypt(Base64.getDecoder().decode(stored.substring(TEXT_PREFIX.length()))),
                StandardCharsets.UTF_8);
    }

    /** IV followed by ciphertext and tag. */
    private byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + body.length).put(iv).put(body).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt resume content", e);
        }
    }

    private byte[] decrypt(byte[] sealed) {
        if (!enabled()) {
            throw new IllegalStateException("Encrypted resume content found, but JMIP_RESUME_ENCRYPTION_KEY is not set");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            // Wrong key or altered data. Nothing about the content goes into the message.
            throw new IllegalStateException("Resume content could not be decrypted", e);
        }
    }
}
