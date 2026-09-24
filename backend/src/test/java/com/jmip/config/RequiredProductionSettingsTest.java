package com.jmip.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredProductionSettingsTest {

    private final RequiredProductionSettings check = new RequiredProductionSettings();

    private static MockEnvironment production() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JMIP_DB_HOST", "db").withProperty("JMIP_DB_NAME", "jmip")
                .withProperty("JMIP_DB_USERNAME", "jmip").withProperty("JMIP_DB_PASSWORD", "secret")
                .withProperty("JMIP_RESUME_DIR", "/data").withProperty("JMIP_OTP_SECRET", "otp")
                .withProperty("JMIP_RESUME_ENCRYPTION_KEY", "key")
                .withProperty("JMIP_VERIFICATION_DELIVERY", "smtp")
                .withProperty("JMIP_MAIL_USERNAME", "sender@example.com").withProperty("JMIP_MAIL_PASSWORD", "app-password")
                .withProperty("JMIP_CORS_ALLOWED_ORIGINS", "https://jmip.example.com");
        environment.setActiveProfiles("prod");
        return environment;
    }

    @Test
    @DisplayName("a complete HTTPS production configuration starts")
    void validProductionPasses() {
        assertThatCode(() -> check.postProcessEnvironment(production(), null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("credentialed CORS only for HTTPS origins, with localhost allowed for trying the image locally")
    void corsOriginsMustBeHttps() {
        assertThatThrownBy(() -> check.postProcessEnvironment(
                production().withProperty("JMIP_CORS_ALLOWED_ORIGINS", "https://jmip.example.com,http://jmip.example.com"), null))
                .hasMessageContaining("https:// origins").hasMessageContaining("http://jmip.example.com");

        assertThatCode(() -> check.postProcessEnvironment(
                production().withProperty("JMIP_CORS_ALLOWED_ORIGINS", "http://localhost:3000,http://127.0.0.1:5173"), null))
                .doesNotThrowAnyException();
        assertThat(RequiredProductionSettings.transportProblems(
                production().withProperty("JMIP_CORS_ALLOWED_ORIGINS", "not a url"))).isNotEmpty();
    }

    @Test
    @DisplayName("the session cookie cannot be made non-Secure or SameSite=None in production")
    void cookieMustStaySecure() {
        assertThatThrownBy(() -> check.postProcessEnvironment(production().withProperty("JMIP_SESSION_COOKIE_SECURE", "false"), null))
                .hasMessageContaining("JMIP_SESSION_COOKIE_SECURE");
        assertThatThrownBy(() -> check.postProcessEnvironment(production().withProperty("JMIP_SESSION_COOKIE_SAME_SITE", "None"), null))
                .hasMessageContaining("JMIP_SESSION_COOKIE_SAME_SITE");
        assertThatCode(() -> check.postProcessEnvironment(production().withProperty("JMIP_SESSION_COOKIE_SAME_SITE", "lax"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sign-up codes may go to the log in production only while every origin is localhost")
    void logDeliveryOnlyLocally() {
        assertThatThrownBy(() -> check.postProcessEnvironment(production().withProperty("JMIP_VERIFICATION_DELIVERY", "log"), null))
                .hasMessageContaining("JMIP_VERIFICATION_DELIVERY=log");
        assertThatThrownBy(() -> check.postProcessEnvironment(production().withProperty("JMIP_VERIFICATION_DELIVERY", "log")
                .withProperty("JMIP_CORS_ALLOWED_ORIGINS", "http://localhost:3000,https://jmip.example.com"), null))
                .hasMessageContaining("JMIP_VERIFICATION_DELIVERY=log");

        // The Docker quick start: the production image on this machine, codes in the log.
        assertThatCode(() -> check.postProcessEnvironment(production().withProperty("JMIP_VERIFICATION_DELIVERY", "log")
                .withProperty("JMIP_CORS_ALLOWED_ORIGINS", "http://localhost:3000,http://localhost:5173,https://localhost"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local development is untouched: none of this applies outside the prod profile")
    void defaultProfileIsUnaffected() {
        MockEnvironment local = new MockEnvironment()
                .withProperty("JMIP_SESSION_COOKIE_SECURE", "false")
                .withProperty("JMIP_CORS_ALLOWED_ORIGINS", "http://192.168.1.20:5173");
        assertThatCode(() -> check.postProcessEnvironment(local, null)).doesNotThrowAnyException();
    }
}
