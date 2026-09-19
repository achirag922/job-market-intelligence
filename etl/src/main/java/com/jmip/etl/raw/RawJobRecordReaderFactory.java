package com.jmip.etl.raw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Chooses a reader for the input file by its extension, so that switching from the
 * synthetic JSON fixture to a real CSV dataset is a change of job parameter, not of code.
 */
@Component
public class RawJobRecordReaderFactory {

    private static final Logger log = LoggerFactory.getLogger(RawJobRecordReaderFactory.class);

    public ItemStreamReader<RawJobRecord> create(Path file, String defaultSource) {
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("Input file does not exist or is not readable: " + file);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            log.info("Reading input as JSON: {}", file);
            return new JsonJobRecordReader(file);
        }
        if (name.endsWith(".csv") || name.endsWith(".tsv")) {
            log.info("Reading input as CSV: {}", file);
            return new CsvJobRecordReader(file, defaultSource);
        }
        throw new IllegalArgumentException(
                "Unsupported input format for " + file + ", expected a .json or .csv file");
    }
}
