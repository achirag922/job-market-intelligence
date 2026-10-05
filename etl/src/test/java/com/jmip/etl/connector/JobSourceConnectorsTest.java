package com.jmip.etl.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.etl.raw.RawJobRecord;
import com.jmip.etl.raw.RawJobRecordReaderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V9.1: connector selection by configuration and job parameter, and the sample connector's mapping. */
class JobSourceConnectorsTest {

    private static JobSourceConnectors connectors(String active, String sampleResource) {
        var properties = new JobSourceConnectors.ConnectorProperties(active,
                new JobSourceConnectors.ConnectorProperties.Sample(sampleResource, "sample-board"));
        return new JobSourceConnectors(List.of(new FileJobSourceConnector(new RawJobRecordReaderFactory()),
                new SampleJobSourceConnector(properties, new DefaultResourceLoader())), properties);
    }

    @Test
    @DisplayName("the configured connector is used unless a run names another; names are case-insensitive")
    void selection() {
        assertThat(connectors("file", "classpath:connectors/sample-postings.json").select(null).name()).isEqualTo("file");
        assertThat(connectors("sample", "classpath:connectors/sample-postings.json").select(" ").name()).isEqualTo("sample");
        assertThat(connectors("file", "classpath:connectors/sample-postings.json").select("SAMPLE").name()).isEqualTo("sample");
        assertThat(connectors("file", "x").names()).containsExactly("file", "sample");
        assertThatThrownBy(() -> connectors("file", "x").select("ftp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown connector 'ftp'; available: file, sample");
    }

    @Test
    @DisplayName("each connector describes its feed without a path; the sample one refuses network locations")
    void feeds() {
        JobSourceConnectors all = connectors("file", "classpath:connectors/sample-postings.json");
        var request = new JobSourceConnector.ConnectorRequest("C:/data/raw/jobs-2026.csv", null);
        assertThat(all.select("file").describe(request)).isEqualTo(new JobSourceConnector.Feed("jobs-2026.csv", "FILE_CSV"));
        assertThat(all.select("sample").describe(request))
                .isEqualTo(new JobSourceConnector.Feed("sample-postings.json", "SAMPLE"));
        assertThatThrownBy(() -> all.select("file").open(new JobSourceConnector.ConnectorRequest(null, null)))
                .hasMessageContaining("'inputFile' is required");

        SampleJobSourceConnector remote = (SampleJobSourceConnector) connectors("sample", "https://jobs.example.com/feed.json")
                .select("sample");
        assertThatThrownBy(remote::resource).hasMessageContaining("classpath: or file: locations only");
    }

    @Test
    @DisplayName("a sample-board record maps to the common raw record, inventing nothing")
    void mapping() throws Exception {
        var node = new ObjectMapper().readTree("""
                {"id": "SB-9", "position": "Backend Developer", "employer": "Tailspin", "city": "Austin",
                 "region": "Texas", "country": "United States", "workplace": "hybrid", "summary": "Build APIs.",
                 "contract": "full-time", "published": "2026-09-10", "link": "https://sample-board.invalid/jobs/SB-9"}
                """);
        RawJobRecord record = SampleJobSourceConnector.map(node, "sample-board");
        assertThat(record.title()).isEqualTo("Backend Developer");
        assertThat(record.company()).isEqualTo("Tailspin");
        assertThat(record.location()).isEqualTo("Austin, Texas, United States");
        assertThat(record.description()).isEqualTo("Build APIs. Workplace: hybrid.");
        assertThat(record.source()).isEqualTo("sample-board");
        assertThat(record.sourceJobId()).isEqualTo("SB-9");
        assertThat(record.sourceUrl()).isEqualTo("https://sample-board.invalid/jobs/SB-9");
        assertThat(record.salary()).isNull();
        assertThat(record.experience()).isNull();
        assertThat(record.companyIndustry()).isNull();
    }
}
