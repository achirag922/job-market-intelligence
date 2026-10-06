package com.jmip.etl.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.etl.raw.RawJobRecord;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V9.1: a mock second source, proving the connector architecture: a "job board" whose records
 * have their own shape ({@code id}, {@code position}, {@code employer}, {@code workplace}, ...),
 * mapped here to {@link RawJobRecord} and then handled by the same pipeline as files.
 *
 * <p>It never contacts anything: the feed is a bundled sample (or another {@code classpath:} or
 * {@code file:} location from configuration). Network locations are refused.
 */
@Component
public class SampleJobSourceConnector implements JobSourceConnector {

    public static final String NAME = "sample";
    public static final String FEED_TYPE = "SAMPLE";

    private final JobSourceConnectors.ConnectorProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public SampleJobSourceConnector(JobSourceConnectors.ConnectorProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ItemStreamReader<RawJobRecord> open(ConnectorRequest request) {
        return new Reader(resource(), properties.sample().sourceCode(), objectMapper);
    }

    @Override
    public Feed describe(ConnectorRequest request) {
        String location = properties.sample().resource();
        return new Feed(location.substring(Math.max(location.lastIndexOf('/'), location.indexOf(':')) + 1), FEED_TYPE);
    }

    Resource resource() {
        String location = properties.sample().resource();
        if (location == null || !(location.startsWith("classpath:") || location.startsWith("file:"))) {
            throw new IllegalArgumentException("The sample connector reads classpath: or file: locations only");
        }
        return resourceLoader.getResource(location);
    }

    /** Maps one sample-board record; fields it does not have are left empty, never invented. */
    static RawJobRecord map(JsonNode node, String sourceCode) {
        String location = Stream.of("city", "region", "country").map(field -> text(node, field))
                .filter(value -> value != null).collect(Collectors.joining(", "));
        String summary = text(node, "summary");
        String workplace = text(node, "workplace");
        // The board states the work mode as a field; the pipeline reads it from the description.
        String description = summary == null ? null : workplace == null ? summary : summary + " Workplace: " + workplace + ".";
        return new RawJobRecord(text(node, "position"), text(node, "employer"), null, null,
                location.isEmpty() ? null : location, description, text(node, "contract"), text(node, "experience"),
                text(node, "compensation"), text(node, "published"), sourceCode, text(node, "link"), text(node, "id"),
                text(node, "closes"), text(node, "state"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    /** Reads the whole (small) sample at open, then hands records out one by one. */
    static final class Reader implements ItemStreamReader<RawJobRecord> {

        private final Resource resource;
        private final String sourceCode;
        private final ObjectMapper objectMapper;
        private Iterator<RawJobRecord> records;

        Reader(Resource resource, String sourceCode, ObjectMapper objectMapper) {
            this.resource = resource;
            this.sourceCode = sourceCode;
            this.objectMapper = objectMapper;
        }

        @Override
        public void open(ExecutionContext executionContext) {
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                JsonNode postings = root.isArray() ? root : root.path("postings");
                List<RawJobRecord> mapped = new ArrayList<>();
                postings.forEach(node -> mapped.add(map(node, sourceCode)));
                records = mapped.iterator();
            } catch (IOException exception) {
                throw new ItemStreamException("Cannot read the sample feed", exception);
            }
        }

        @Override
        public RawJobRecord read() {
            return records != null && records.hasNext() ? records.next() : null;
        }
    }
}
