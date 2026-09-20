package com.jmip.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.jmip.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real provider, talking to Claude through the official SDK.
 *
 * <p>This is the only class in the application that knows a model vendor exists. It takes
 * text and returns text; nothing provider-shaped leaves it, so replacing the vendor means
 * writing a sibling of this class and changing one property.
 *
 * <p>Failures are translated rather than propagated. A provider exception can carry
 * request details, and those must not reach a user or a log line, so the cause is kept for
 * the stack trace and the message the caller sees is generic.
 */
public class AnthropicAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAiClient.class);

    private final AiProperties properties;
    private final AnthropicClient client;

    public AnthropicAiClient(AiProperties properties) {
        this.properties = properties;
        // Built only when there is a credential to build it with. The SDK rejects a blank
        // key at construction, and an application with no assistant configured still has
        // to start: every other feature works without one.
        this.client = properties.hasApiKey()
                ? AnthropicOkHttpClient.builder()
                        .apiKey(properties.apiKey())
                        .timeout(properties.timeout())
                        .build()
                : null;
        // The model is safe to log and useful when answers change after a config change.
        // The key is not, and is never logged anywhere in this class.
        log.info("Anthropic AI client created, model={}, timeout={}, configured={}",
                properties.model(), properties.timeout(), properties.hasApiKey());
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    @Override
    public boolean isAvailable() {
        return client != null;
    }

    @Override
    public String complete(AiCompletionRequest request) {
        if (!isAvailable()) {
            throw new AiUnavailableException("No AI API key is configured");
        }

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(request.maxTokens())
                // Both tasks here are short and mechanical — pull fields out of a
                // sentence, or describe a handful of rows. Low effort is the cheap
                // setting that still does them well.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .system(request.system())
                .addUserMessage(request.user());

        // Only sent when configured: the current models reject sampling parameters with a
        // 400, so an unconditional temperature would break the default setup.
        if (properties.temperature() != null) {
            params.temperature(properties.temperature());
        }

        try {
            Message response = client.messages().create(params.build());
            return textOf(response);
        } catch (AnthropicServiceException exception) {
            // The provider's own message may quote the request, so it is logged at debug
            // and never returned.
            log.warn("AI provider returned an error: {}", exception.getClass().getSimpleName());
            log.debug("AI provider error detail", exception);
            throw new AiFailureException("AI provider call failed", exception);
        } catch (RuntimeException exception) {
            log.warn("AI provider call failed: {}", exception.getClass().getSimpleName());
            log.debug("AI provider failure detail", exception);
            throw new AiFailureException("AI provider call failed", exception);
        }
    }

    /**
     * Concatenates the text blocks of a reply.
     *
     * <p>A response can carry more than text — reasoning blocks in particular — and those
     * are skipped rather than parsed. An empty result is a failure, not an empty answer:
     * it means the reply was truncated or refused, and treating it as a valid answer would
     * show the user a blank response.
     */
    private String textOf(Message response) {
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .reduce("", (left, right) -> left + right)
                .trim();

        if (text.isEmpty()) {
            throw new AiFailureException("AI provider returned no text content");
        }
        return text;
    }
}
