package com.jmip.etl.connector;

import com.jmip.etl.raw.RawJobRecord;
import org.springframework.batch.item.ItemStreamReader;

/**
 * V9.1: where job records come from. A connector only obtains and parses its source's records
 * into {@link RawJobRecord}s; everything after that is the one common pipeline every source
 * shares: the processor normalises and validates (V8.2), the writer deduplicates and loads,
 * and the source registry tracks source metadata and counts (V8.1).
 *
 * <p>Connector → Normalize → Validate → Deduplicate → Load.
 */
public interface JobSourceConnector {

    /** The name configuration uses to select it: {@code jmip.etl.connectors.active} or the {@code connector} job parameter. */
    String name();

    /** Opens a reader over this connector's records for one run. */
    ItemStreamReader<RawJobRecord> open(ConnectorRequest request);

    /** What the run reads, for monitoring: a name and a type, never a path, URL or credential. */
    Feed describe(ConnectorRequest request);

    /** The run's job parameters a connector may use. */
    record ConnectorRequest(String inputFile, String defaultSource) {
    }

    /**
     * @param type FILE_JSON, FILE_CSV, SAMPLE, ...; stored with the run
     */
    record Feed(String name, String type) {
    }
}
