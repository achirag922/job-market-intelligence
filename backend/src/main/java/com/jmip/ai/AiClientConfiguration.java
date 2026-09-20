package com.jmip.ai;

import com.jmip.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the provider from configuration, so the rest of the application depends on the
 * {@link AiClient} interface and never on a vendor.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiClientConfiguration.class);

    @Bean
    public AiClient aiClient(AiProperties properties) {
        String provider = properties.provider() == null ? "" : properties.provider().trim();

        if ("stub".equalsIgnoreCase(provider)) {
            return new StubAiClient();
        }
        if (!"anthropic".equalsIgnoreCase(provider)) {
            throw new IllegalStateException(
                    "Unknown jmip.ai.provider '" + provider + "'. Supported: anthropic, stub");
        }
        if (!properties.hasApiKey()) {
            // Starting without a key is normal — most of this application has nothing to
            // do with the assistant, and it should not refuse to boot over an unset
            // variable. The assistant endpoint reports itself unavailable instead.
            log.warn("jmip.ai.provider=anthropic but no API key is set; "
                    + "the assistant will report itself unavailable");
        }
        return new AnthropicAiClient(properties);
    }
}
