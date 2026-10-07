package com.jmip.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Stops a {@code prod} start before anything connects when a required setting is absent.
 *
 * <p>Spring leaves an unset {@code ${VAR}} in place as literal text, so without this check a
 * missing variable surfaces much later as a confusing error — a database host called
 * "${JMIP_DB_HOST}", or a resume folder of that name created in the working directory.
 * Registered in {@code META-INF/spring.factories}; does nothing outside {@code prod}.
 */
public class RequiredProductionSettings implements EnvironmentPostProcessor {

    static final List<String> REQUIRED = List.of(
            "JMIP_DB_HOST", "JMIP_DB_NAME", "JMIP_DB_USERNAME", "JMIP_DB_PASSWORD",
            "JMIP_RESUME_DIR", "JMIP_CORS_ALLOWED_ORIGINS", "JMIP_OTP_SECRET", "JMIP_RESUME_ENCRYPTION_KEY");

    /** V10.1: the PostgreSQL driver's sslmode values; managed cloud databases usually want require or verify-full. */
    static final List<String> SSL_MODES = List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    /** Needed unless verification codes are explicitly logged instead of emailed. */
    static final List<String> REQUIRED_FOR_SMTP = List.of("JMIP_MAIL_USERNAME", "JMIP_MAIL_PASSWORD");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        boolean emailsCodes = !"log".equalsIgnoreCase(environment.getProperty("JMIP_VERIFICATION_DELIVERY", "smtp"));
        List<String> missing = java.util.stream.Stream.concat(REQUIRED.stream(),
                        emailsCodes ? REQUIRED_FOR_SMTP.stream() : java.util.stream.Stream.<String>empty())
                .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            // Names only, never values.
            throw new IllegalStateException(
                    "The prod profile requires these environment variables: " + String.join(", ", missing));
        }
        List<String> unsafe = new java.util.ArrayList<>(transportProblems(environment));
        unsafe.addAll(secretProblems(environment));
        String sslMode = environment.getProperty("JMIP_DB_SSL_MODE", "prefer").strip().toLowerCase(java.util.Locale.ROOT);
        if (!SSL_MODES.contains(sslMode)) {
            unsafe.add("JMIP_DB_SSL_MODE must be one of " + String.join(", ", SSL_MODES));
        }
        if (!emailsCodes && !localOnly(environment)) {
            // Anyone who can read the logs could then confirm any address.
            unsafe.add("JMIP_VERIFICATION_DELIVERY=log writes sign-up codes to the log; use smtp when "
                    + "JMIP_CORS_ALLOWED_ORIGINS serves anything other than localhost");
        }
        if (!unsafe.isEmpty()) {
            throw new IllegalStateException("Unsafe production settings: " + String.join("; ", unsafe));
        }
    }

    /**
     * V6.10.6: in production the session cookie must be Secure and not SameSite=None, and the
     * browser origins allowed to send it must be HTTPS. Plain-HTTP localhost stays allowed, so
     * the production image can still be tried locally (browsers treat localhost as secure).
     */
    static List<String> transportProblems(ConfigurableEnvironment environment) {
        List<String> problems = new java.util.ArrayList<>();
        if ("false".equalsIgnoreCase(environment.getProperty("JMIP_SESSION_COOKIE_SECURE", "true").strip())) {
            problems.add("JMIP_SESSION_COOKIE_SECURE must not be false (the session cookie would travel over plain HTTP)");
        }
        if ("none".equalsIgnoreCase(environment.getProperty("JMIP_SESSION_COOKIE_SAME_SITE", "strict").strip())) {
            problems.add("JMIP_SESSION_COOKIE_SAME_SITE must be strict or lax, not none");
        }
        for (String origin : environment.getProperty("JMIP_CORS_ALLOWED_ORIGINS", "").split(",")) {
            String trimmed = origin.strip();
            if (!trimmed.isEmpty() && !isTrustedOrigin(trimmed)) {
                problems.add("JMIP_CORS_ALLOWED_ORIGINS must list https:// origins (or http://localhost), not " + trimmed);
            }
        }
        return problems;
    }

    /** V10.5: shortest accepted secrets. Long random values are the only defence against offline guessing. */
    static final int MIN_OTP_SECRET_LENGTH = 32;
    static final int MIN_METRICS_PASSWORD_LENGTH = 16;

    /**
     * V10.5: production secrets must be strong enough to resist guessing. Only the variable names are
     * reported, never their values or lengths.
     */
    static List<String> secretProblems(ConfigurableEnvironment environment) {
        List<String> problems = new java.util.ArrayList<>();
        String otpSecret = environment.getProperty("JMIP_OTP_SECRET", "");
        if (StringUtils.hasText(otpSecret) && otpSecret.strip().length() < MIN_OTP_SECRET_LENGTH) {
            problems.add("JMIP_OTP_SECRET must be at least " + MIN_OTP_SECRET_LENGTH
                    + " characters (e.g. openssl rand -hex 32)");
        }
        String metricsPassword = environment.getProperty("JMIP_METRICS_PASSWORD", "");
        if (StringUtils.hasText(metricsPassword) && metricsPassword.strip().length() < MIN_METRICS_PASSWORD_LENGTH) {
            problems.add("JMIP_METRICS_PASSWORD must be at least " + MIN_METRICS_PASSWORD_LENGTH
                    + " characters when set");
        }
        return problems;
    }

    /** True when every allowed origin is this machine: the image is being tried out locally. */
    private static boolean localOnly(ConfigurableEnvironment environment) {
        for (String origin : environment.getProperty("JMIP_CORS_ALLOWED_ORIGINS", "").split(",")) {
            String host;
            try {
                host = java.net.URI.create(origin.strip()).getHost();
            } catch (IllegalArgumentException malformed) {
                return false;
            }
            if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTrustedOrigin(String origin) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(origin);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
            return true;
        }
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    }
}
