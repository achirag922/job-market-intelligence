package com.jmip.config;

import com.jmip.common.exception.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V10.8: OpenAPI 3 description of the REST API, served at /v3/api-docs with Swagger UI at /swagger-ui.html.
 * Enabled locally, off in the prod profile (JMIP_API_DOCS_ENABLED). Everything here is documentation:
 * controllers and their behaviour are unchanged.
 */
@Configuration
public class OpenApiConfig {

    static final String SESSION = "session";
    static final String CSRF = "csrf";
    private static final String ERROR_REF = "#/components/schemas/ApiError";

    /** Routes that need no session (mirrors SecurityConfig). */
    static final Set<String> PUBLIC = Set.of("/api/auth/signup", "/api/auth/login", "/api/auth/logout",
            "/api/auth/verify-email", "/api/auth/resend-verification", "/api/auth/me", "/api/public/profiles/{slug}");
    /** POST routes with a per-address rate limit (mirrors RequestLimitFilter). */
    private static final Set<String> RATE_LIMITED = Set.of("/api/auth/login", "/api/auth/signup", "/api/auth/verify-email",
            "/api/auth/resend-verification", "/api/resumes", "/api/assistant/query");

    /** Controller tag (springdoc's default, from the class name) to a readable name and description. */
    private static final Map<String, String[]> TAGS = new LinkedHashMap<>();

    static {
        tag("auth-controller", "Authentication", "Sign up, verify the email code, sign in and out, current session");
        tag("account-controller", "Account", "Name, password change and account deletion");
        tag("onboarding-controller", "Onboarding", "First-run setup steps");
        tag("match-preferences-controller", "Match preferences", "Preferences that weight job matching");
        tag("notification-controller", "Notifications", "Notification centre, read state and preferences");
        tag("job-controller", "Jobs", "Job search with filters and pagination, job details, salary currencies");
        tag("personalized-job-controller", "Recommendations", "Jobs ranked for the signed-in user, with reasons");
        tag("saved-job-controller", "Saved jobs", "Save jobs, application status, notes, priority, follow-up date");
        tag("application-controller", "Applications", "Application insights and funnel");
        tag("workspace-controller", "Job workspace", "Matches, hidden jobs, saved searches, viewed jobs");
        tag("job-alert-controller", "Job alerts", "Saved alert criteria and their digests");
        tag("resume-controller", "Resumes", "Upload (PDF), analysis, skills and job match");
        tag("resume-version-controller", "Resume versions", "Versions of a resume and their job analysis");
        tag("resume-builder-controller", "Resume builder", "Build a resume from structured content; PDF export");
        tag("career-goal-controller", "Career goals", "Target role, skill gap and roadmap");
        tag("learning-controller", "Learning", "Learning plan items and resources");
        tag("interview-controller", "Interviews", "Practice sessions: questions, answers, evaluation, report");
        tag("career-progress-controller", "Career progress", "Readiness score, achievements and milestones");
        tag("dashboard-controller", "Personal dashboard", "My career overview and personal analytics");
        tag("portfolio-controller", "Portfolio", "Edit, publish and unpublish the public portfolio");
        tag("public-profile-controller", "Public profiles", "Published portfolios, readable without signing in");
        tag("analytics-controller", "Market analytics", "Categories, skills, trends and other market aggregates");
        tag("skill-controller", "Skills", "Skill demand");
        tag("company-controller", "Companies", "Company analytics");
        tag("location-controller", "Locations", "Location analytics");
        tag("market-controller", "Market intelligence", "Role-level market insights and trend estimates");
        tag("assistant-controller", "AI assistant", "Questions answered from JMIP data (needs an AI provider)");
        tag("admin-controller", "Administration", "Platform overview, data quality and users (ADMIN role)");
        tag("etl-run-controller", "ETL runs", "Ingestion run history and status");
        tag("job-source-controller", "Job sources", "Sources seen by the ETL");
    }

    private static void tag(String controller, String name, String description) {
        TAGS.put(controller, new String[] {name, description});
    }

    @Bean
    OpenAPI jmipOpenApi() {
        Components components = new Components()
                .addSecuritySchemes(SESSION, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE).name("JMIP_SESSION")
                        .description("Session cookie set by POST /api/auth/login (HttpOnly; sent automatically by the browser)."))
                .addSecuritySchemes(CSRF, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN")
                        .description("The csrfToken returned by login and /api/auth/me; required on POST, PUT, PATCH and DELETE once signed in."));
        return new OpenAPI()
                .info(new Info().title("JMIP API").version("v1").description("""
                        REST API of the Job Market Intelligence Platform.

                        **Authentication.** Sign up (`POST /api/auth/signup`), confirm the 6-digit email code
                        (`POST /api/auth/verify-email`), then sign in (`POST /api/auth/login`). Login sets the
                        `JMIP_SESSION` cookie and returns a `csrfToken`; send it as `X-CSRF-TOKEN` on every
                        state-changing request. In Swagger UI on the same origin, the browser sends the cookie
                        for you after you call login. Locally, the code is written to the backend log
                        (`JMIP_VERIFICATION_DELIVERY=log`).

                        **Authorisation.** Every route needs a signed-in user except those marked as public;
                        `/api/admin/**` needs the ADMIN role. Data is always that of the signed-in user.

                        **Errors.** One JSON shape (`ApiError`): `status`, `error`, `message`, `path` and, for
                        validation errors, `fieldErrors`. Server errors never include internal details.

                        **Pagination.** List endpoints that page take `page` (0-based), `size` (default 20,
                        max 100) and `sort` (`field,asc|desc`, whitelisted per endpoint) and return
                        `content`, `page`, `size`, `totalElements` and `totalPages`.
                        """))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(SESSION));
    }

    @Bean
    GlobalOpenApiCustomizer jmipOpenApiCustomizer() {
        return OpenApiConfig::customize;
    }

    static void customize(OpenAPI api) {
        // Each group is generated separately, so the shared error schema is added to every document here.
        if (api.getComponents() == null) {
            api.setComponents(new Components());
        }
        ModelConverters.getInstance().read(ApiError.class).forEach((name, schema) -> {
            if (api.getComponents().getSchemas() == null || !api.getComponents().getSchemas().containsKey(name)) {
                api.getComponents().addSchemas(name, schema);
            }
        });
        renameTags(api);
        if (api.getPaths() == null) {
            return;
        }
        api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            boolean isPublic = PUBLIC.contains(path);
            if (isPublic) {
                operation.setSecurity(List.of());
            } else if (method != PathItem.HttpMethod.GET) {
                operation.addSecurityItem(new SecurityRequirement().addList(SESSION).addList(CSRF));
            }
            addErrors(path, method, operation, isPublic);
            addExamples(path, method, operation);
        }));
    }

    private static void renameTags(OpenAPI api) {
        Set<String> used = new java.util.HashSet<>();
        if (api.getPaths() != null) {
            api.getPaths().values().forEach(item -> item.readOperations().forEach(operation -> {
                if (operation.getTags() != null) {
                    used.addAll(operation.getTags());
                    operation.setTags(operation.getTags().stream()
                            .map(tag -> TAGS.containsKey(tag) ? TAGS.get(tag)[0] : tag).toList());
                }
            }));
        }
        // Only the tags this document (or group) actually uses, with readable names and descriptions.
        List<Tag> tags = new ArrayList<>();
        TAGS.forEach((controller, value) -> {
            if (used.contains(controller)) {
                tags.add(new Tag().name(value[0]).description(value[1]));
            }
        });
        api.setTags(tags);
    }

    private static void addErrors(String path, PathItem.HttpMethod method, Operation operation, boolean isPublic) {
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        error(responses, "400", "Invalid request: validation failed, bad parameter or unreadable JSON");
        if (!isPublic) {
            error(responses, "401", "Not signed in");
            error(responses, "403", path.startsWith("/api/admin")
                    ? "Signed in without the ADMIN role, or missing/invalid CSRF token"
                    : "Missing or invalid CSRF token on a state-changing request");
        }
        if (path.contains("{")) {
            error(responses, "404", "Not found, or not yours");
        }
        boolean interviewAi = path.startsWith("/api/interviews/") && (path.endsWith("/answer") || path.endsWith("/evaluate"));
        if (method == PathItem.HttpMethod.POST && (RATE_LIMITED.contains(path) || interviewAi)) {
            error(responses, "429", "Too many requests from this address; retry after a minute");
        }
        error(responses, "500", "Unexpected server error (no internal details; quote the X-Request-Id header)");
        operation.setResponses(responses);
    }

    private static void error(ApiResponses responses, String code, String description) {
        if (!responses.containsKey(code)) {
            responses.addApiResponse(code, new ApiResponse().description(description).content(new Content()
                    .addMediaType("application/json", new MediaType().schema(new Schema<>().$ref(ERROR_REF)))));
        }
    }

    private static void addExamples(String path, PathItem.HttpMethod method, Operation operation) {
        String key = method + " " + path;
        switch (key) {
            case "POST /api/auth/signup" -> {
                operation.setSummary("Create an account; a 6-digit code is sent to the email");
                body(operation, "{\"fullName\":\"Alex Rivera\",\"email\":\"alex@example.com\",\"password\":\"a-long-passphrase-123\"}");
            }
            case "POST /api/auth/verify-email" -> {
                operation.setSummary("Confirm the email with the 6-digit code (10 minutes, 5 attempts)");
                body(operation, "{\"email\":\"alex@example.com\",\"code\":\"123456\"}");
            }
            case "POST /api/auth/login" -> {
                operation.setSummary("Sign in; sets the JMIP_SESSION cookie and returns the csrfToken");
                body(operation, "{\"email\":\"alex@example.com\",\"password\":\"a-long-passphrase-123\"}");
            }
            case "GET /api/auth/me" -> operation.setSummary("Current user and csrfToken; 401 when not signed in");
            case "GET /api/jobs" -> {
                operation.setSummary("Search jobs");
                operation.setDescription("""
                        Filters (all optional, combined with AND): `q` (free text), `title`, `location`, `company`,
                        `skill`, `employmentType`, `category`, `experience`, `salaryMin`/`salaryMax` with `currency`,
                        `locationStated`. `order=match` ranks by skill match with your current resume;
                        `usePreferences=true` ranks by your match preferences; `excludeHidden=true` leaves out jobs
                        marked not interested. Paged: `page`, `size` (default 20, max 100), `sort`.
                        Example: `GET /api/jobs?skill=Java&location=Berlin&page=0&size=20&sort=postedDate,desc`
                        """);
            }
            case "POST /api/resumes" -> operation.setSummary("Upload a resume (multipart `file`, PDF only, at most 5 MB)");
            default -> {
                // Other operations keep the description generated from the controller.
            }
        }
    }

    private static void body(Operation operation, String json) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return;
        }
        MediaType mediaType = operation.getRequestBody().getContent().get("application/json");
        if (mediaType != null) {
            mediaType.addExamples("example", new Example().value(json));
        }
    }

    // ---------------------------------------------------------------- groups (Swagger UI drop-down)

    @Bean
    GroupedOpenApi allApis() {
        return GroupedOpenApi.builder().group("all").displayName("All endpoints").pathsToMatch("/api/**").build();
    }

    @Bean
    GroupedOpenApi accountApis() {
        return GroupedOpenApi.builder().group("account").displayName("Account & profile")
                .pathsToMatch("/api/auth/**", "/api/account/**", "/api/onboarding/**", "/api/match-preferences/**",
                        "/api/notifications/**", "/api/portfolio/**", "/api/public/**")
                .build();
    }

    @Bean
    GroupedOpenApi jobApis() {
        return GroupedOpenApi.builder().group("jobs").displayName("Jobs & applications")
                .pathsToMatch("/api/jobs/**", "/api/saved-jobs/**", "/api/applications/**", "/api/workspace/**",
                        "/api/job-alerts/**")
                .build();
    }

    @Bean
    GroupedOpenApi careerApis() {
        return GroupedOpenApi.builder().group("career").displayName("Resume & career growth")
                .pathsToMatch("/api/resumes/**", "/api/career-goals/**", "/api/learning/**", "/api/interviews/**",
                        "/api/career-progress/**", "/api/dashboard/**", "/api/assistant/**")
                .build();
    }

    @Bean
    GroupedOpenApi marketApis() {
        return GroupedOpenApi.builder().group("market").displayName("Market analytics")
                .pathsToMatch("/api/analytics/**", "/api/skills/**", "/api/companies/**", "/api/locations/**",
                        "/api/market/**")
                .build();
    }

    @Bean
    GroupedOpenApi adminApis() {
        return GroupedOpenApi.builder().group("admin").displayName("Administration & ETL")
                .pathsToMatch("/api/admin/**", "/api/etl/**", "/api/job-sources/**")
                .build();
    }
}
