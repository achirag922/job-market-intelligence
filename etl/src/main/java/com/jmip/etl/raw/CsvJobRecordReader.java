package com.jmip.etl.raw;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streams a CSV dataset using Apache Commons CSV.
 *
 * <p>Column names differ between publishers, so each {@link RawJobRecord} field accepts
 * several header spellings. Matching ignores case, spaces and underscores, which covers
 * the usual variation ({@code company_name}, {@code "Company Name"}, {@code companyName})
 * without needing a mapping file per dataset.
 */
public class CsvJobRecordReader implements ItemStreamReader<RawJobRecord> {

    private static final Map<String, List<String>> COLUMN_ALIASES = Map.ofEntries(
            Map.entry("title", List.of("title", "jobtitle", "position", "name")),
            Map.entry("company", List.of("company", "companyname", "employer", "organization")),
            Map.entry("companyIndustry", List.of("companyindustry", "industry", "sector")),
            Map.entry("companyWebsite", List.of("companywebsite", "website", "companyurl")),
            Map.entry("location", List.of("location", "joblocation", "place", "city")),
            Map.entry("description", List.of("description", "jobdescription", "details", "summary")),
            Map.entry("employmentType", List.of("employmenttype", "jobtype", "worktype", "contracttype")),
            Map.entry("experience", List.of("experience", "experiencerequired", "yearsofexperience", "exp")),
            Map.entry("salary", List.of("salary", "compensation", "pay", "salaryrange")),
            Map.entry("postedDate", List.of("posteddate", "datePosted", "publicationdate", "date")),
            Map.entry("source", List.of("source", "site", "board")),
            Map.entry("sourceUrl", List.of("sourceurl", "url", "link", "joburl")));

    private final Path file;
    private final String defaultSource;

    private BufferedReader reader;
    private CSVParser parser;
    private Iterator<CSVRecord> rows;
    private Map<String, String> resolvedColumns;

    /**
     * @param defaultSource used when the file has no source column, so that every row
     *                      still records where it came from
     */
    public CsvJobRecordReader(Path file, String defaultSource) {
        this.file = file;
        this.defaultSource = defaultSource;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreSurroundingSpaces(true)
                    .setIgnoreEmptyLines(true)
                    .build()
                    .parse(reader);
            rows = parser.iterator();
            resolvedColumns = resolveColumns(parser.getHeaderMap().keySet());
        } catch (IOException e) {
            throw new ItemStreamException("Unable to open " + file, e);
        }
    }

    @Override
    public RawJobRecord read() {
        if (!rows.hasNext()) {
            return null;
        }
        CSVRecord row = rows.next();
        String source = value(row, "source");
        return new RawJobRecord(
                value(row, "title"),
                value(row, "company"),
                value(row, "companyIndustry"),
                value(row, "companyWebsite"),
                value(row, "location"),
                value(row, "description"),
                value(row, "employmentType"),
                value(row, "experience"),
                value(row, "salary"),
                value(row, "postedDate"),
                source == null ? defaultSource : source,
                value(row, "sourceUrl"));
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // See JsonJobRecordReader: restarts re-read the file and rely on the database's
        // duplicate constraints rather than on a stored read offset.
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (parser != null) {
                parser.close();
            }
            if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
            throw new ItemStreamException("Unable to close " + file, e);
        }
    }

    private static Map<String, String> resolveColumns(Set<String> headers) {
        Map<String, String> normalisedHeaders = new java.util.HashMap<>();
        for (String header : headers) {
            normalisedHeaders.put(normalise(header), header);
        }
        Map<String, String> resolved = new java.util.HashMap<>();
        COLUMN_ALIASES.forEach((field, aliases) -> aliases.stream()
                .map(CsvJobRecordReader::normalise)
                .filter(normalisedHeaders::containsKey)
                .findFirst()
                .ifPresent(alias -> resolved.put(field, normalisedHeaders.get(alias))));
        return resolved;
    }

    private static String normalise(String header) {
        return header.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String value(CSVRecord row, String field) {
        String column = resolvedColumns.get(field);
        if (column == null || !row.isSet(column)) {
            return null;
        }
        String value = row.get(column);
        return value == null || value.isBlank() ? null : value;
    }
}
