package com.jmip.etl.connector;

import com.jmip.etl.raw.RawJobRecord;
import com.jmip.etl.raw.RawJobRecordReaderFactory;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;

/**
 * V9.1: the existing source, unchanged: a JSON or CSV file named by the {@code inputFile} job
 * parameter, read by the V1 readers chosen by extension.
 */
@Component
public class FileJobSourceConnector implements JobSourceConnector {

    public static final String CONNECTOR_NAME = "file";

    private final RawJobRecordReaderFactory readerFactory;

    public FileJobSourceConnector(RawJobRecordReaderFactory readerFactory) {
        this.readerFactory = readerFactory;
    }

    @Override
    public String name() {
        return CONNECTOR_NAME;
    }

    @Override
    public ItemStreamReader<RawJobRecord> open(ConnectorRequest request) {
        if (request.inputFile() == null || request.inputFile().isBlank()) {
            throw new IllegalArgumentException(
                    "Job parameter 'inputFile' is required, for example: inputFile=etl/data/raw/dataset.json");
        }
        return readerFactory.create(Path.of(request.inputFile()),
                request.defaultSource() == null ? "unknown" : request.defaultSource());
    }

    /** The file's name only, never its path. */
    @Override
    public Feed describe(ConnectorRequest request) {
        if (request.inputFile() == null || request.inputFile().isBlank()) {
            return new Feed(null, null);
        }
        Path file = Path.of(request.inputFile()).getFileName();
        String name = file == null ? null : file.toString();
        return new Feed(name, typeOf(name));
    }

    static String typeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return "FILE_JSON";
        }
        return name.endsWith(".csv") || name.endsWith(".tsv") ? "FILE_CSV" : "OTHER";
    }
}
