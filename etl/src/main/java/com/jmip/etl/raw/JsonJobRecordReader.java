package com.jmip.etl.raw;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ExecutionContext;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;

/**
 * Streams a JSON array of job postings, one object at a time, so that file size is
 * bounded by the largest record rather than the whole document.
 *
 * <p>The fixture stores location, experience and salary as structured fields, while
 * {@link RawJobRecord} is deliberately all text. This reader composes them into the
 * textual forms a real dataset would publish, so the processor has exactly one parsing
 * path regardless of input format.
 */
public class JsonJobRecordReader implements ItemStreamReader<RawJobRecord> {

    private final Path file;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private InputStream inputStream;
    private JsonParser parser;

    public JsonJobRecordReader(Path file) {
        this.file = file;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            inputStream = Files.newInputStream(file);
            parser = objectMapper.getFactory().createParser(inputStream);
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new ItemStreamException("Expected a JSON array at the root of " + file);
            }
        } catch (IOException e) {
            throw new ItemStreamException("Unable to open " + file, e);
        }
    }

    @Override
    public RawJobRecord read() throws Exception {
        if (parser.nextToken() == JsonToken.END_ARRAY) {
            return null;
        }
        return toRawRecord(objectMapper.readTree(parser));
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // No incremental state: a restart re-reads the file from the beginning, and the
        // database's duplicate constraints make that safe.
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (parser != null) {
                parser.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            throw new ItemStreamException("Unable to close " + file, e);
        }
    }

    private RawJobRecord toRawRecord(JsonNode node) {
        return new RawJobRecord(
                text(node, "title"),
                text(node, "company_name"),
                text(node, "company_industry"),
                text(node, "company_website"),
                composeLocation(node),
                text(node, "description"),
                text(node, "employment_type"),
                composeExperience(node),
                composeSalary(node),
                text(node, "posted_date"),
                text(node, "source"),
                text(node, "source_url"));
    }

    /** "Austin, Texas, United States", dropping the parts the source left out. */
    private String composeLocation(JsonNode node) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("city", "state", "country")) {
            String value = text(node, field);
            if (value != null) {
                parts.add(value);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** "3-5 years", or "5+ years" when the source gives no upper bound. */
    private String composeExperience(JsonNode node) {
        BigDecimal min = number(node, "experience_min");
        BigDecimal max = number(node, "experience_max");
        if (min == null && max == null) {
            return null;
        }
        if (min != null && max != null) {
            return min.toPlainString() + "-" + max.toPlainString() + " years";
        }
        return (min != null ? min.toPlainString() + "+" : "up to " + max.toPlainString()) + " years";
    }

    /** "120000-150000 USD", or "from 120000 USD" when the range is open ended. */
    private String composeSalary(JsonNode node) {
        BigDecimal min = number(node, "salary_min");
        BigDecimal max = number(node, "salary_max");
        String currency = text(node, "currency");
        if (min == null && max == null) {
            return null;
        }
        String amounts;
        if (min != null && max != null) {
            amounts = min.toPlainString() + "-" + max.toPlainString();
        } else if (min != null) {
            amounts = "from " + min.toPlainString();
        } else {
            amounts = "up to " + max.toPlainString();
        }
        return currency == null ? amounts : amounts + " " + currency;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }
}
