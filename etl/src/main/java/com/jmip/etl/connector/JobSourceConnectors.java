package com.jmip.etl.connector;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * V9.1: the available connectors, and which one a run uses: the {@code connector} job parameter
 * when given, otherwise {@code jmip.etl.connectors.active} (JMIP_ETL_CONNECTOR), which defaults to
 * {@code file}, the existing CSV/JSON source, so existing commands behave exactly as before.
 */
@Component
@EnableConfigurationProperties(JobSourceConnectors.ConnectorProperties.class)
public class JobSourceConnectors {

    /**
     * @param active the connector used when a run does not name one
     * @param sample settings of the mock sample connector
     */
    @ConfigurationProperties(prefix = "jmip.etl.connectors")
    public record ConnectorProperties(@DefaultValue("file") String active, @DefaultValue Sample sample) {

        /**
         * @param resource   where the sample feed is read from: a {@code classpath:} or {@code file:} location only
         * @param sourceCode the source code its postings are recorded under
         */
        public record Sample(@DefaultValue("classpath:connectors/sample-postings.json") String resource,
                             @DefaultValue("sample-board") String sourceCode) {
        }
    }

    private final Map<String, JobSourceConnector> byName = new TreeMap<>();
    private final ConnectorProperties properties;

    public JobSourceConnectors(List<JobSourceConnector> connectors, ConnectorProperties properties) {
        connectors.forEach(connector -> byName.put(connector.name(), connector));
        this.properties = properties;
    }

    /** @throws IllegalArgumentException for a name no connector has */
    public JobSourceConnector select(String requested) {
        String name = (requested == null || requested.isBlank() ? properties.active() : requested)
                .strip().toLowerCase(Locale.ROOT);
        JobSourceConnector connector = byName.get(name);
        if (connector == null) {
            throw new IllegalArgumentException("Unknown connector '" + name + "'; available: " + String.join(", ", byName.keySet()));
        }
        return connector;
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
