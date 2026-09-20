package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How the assistant talks to a model provider.
 *
 * <p>The API key is read from configuration, which in practice means an environment
 * variable. It is never written to a file in this repository, never logged, and never
 * reaches the frontend — every model call happens on the server.
 *
 * @param provider    which {@code AiClient} implementation to use: {@code anthropic} for
 *                    the real provider, {@code stub} for a deterministic local one that
 *                    needs no key and no network
 * @param apiKey      provider credential. Blank means the assistant reports itself
 *                    unavailable rather than failing at the first question
 * @param model       model identifier, so upgrading does not need a recompile
 * @param temperature sampling temperature, sent only when set. Left unset by default
 *                    because the current Claude models reject it outright — they removed
 *                    sampling controls, and sending one is a 400 rather than a no-op. It
 *                    remains configurable for models that still accept it
 * @param maxTokens   ceiling on the response, which bounds both cost and latency. It has
 *                    to cover reasoning tokens as well as the visible reply, so it is
 *                    larger than the JSON this application actually reads
 * @param timeout     how long one call may take before it is abandoned
 */
@ConfigurationProperties(prefix = "jmip.ai")
public record AiProperties(
        @DefaultValue("anthropic") String provider,
        @DefaultValue("") String apiKey,
        @DefaultValue("claude-opus-5") String model,
        Double temperature,
        @DefaultValue("8192") int maxTokens,
        @DefaultValue("30s") Duration timeout) {

    /** True when a real provider call could be made. Never logs or exposes the key. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
