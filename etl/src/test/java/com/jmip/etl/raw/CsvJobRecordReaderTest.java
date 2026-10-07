package com.jmip.etl.raw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V10.9: CSV feeds with differently named columns map onto the common raw record. */
class CsvJobRecordReaderTest {

    @TempDir
    Path dir;

    private CsvJobRecordReader reader(String csv, String defaultSource) throws Exception {
        Path file = Files.writeString(dir.resolve("feed.csv"), csv);
        CsvJobRecordReader reader = new CsvJobRecordReader(file, defaultSource);
        reader.open(new ExecutionContext());
        return reader;
    }

    @Test
    @DisplayName("header aliases in any spelling map to the right fields; blanks become null")
    void aliasesAndBlanks() throws Exception {
        CsvJobRecordReader reader = reader("""
                Job Title,Employer,Job Location,Job Type,Date Posted,Posting Status,Salary Range,URL
                Backend Engineer,Acme,"Berlin, Germany",Full-time,2026-09-01,open,,https://jobs.example/1
                """, "partner-feed");

        RawJobRecord record = reader.read();
        assertThat(record.title()).isEqualTo("Backend Engineer");
        assertThat(record.company()).isEqualTo("Acme");
        assertThat(record.location()).isEqualTo("Berlin, Germany");
        assertThat(record.employmentType()).isEqualTo("Full-time");
        assertThat(record.postedDate()).isEqualTo("2026-09-01");
        assertThat(record.status()).isEqualTo("open");
        assertThat(record.salary()).isNull();
        assertThat(record.sourceUrl()).isEqualTo("https://jobs.example/1");
        // No source column: the run's default source is used.
        assertThat(record.source()).isEqualTo("partner-feed");
        assertThat(record.description()).isNull();
        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("a source column wins over the default source; empty lines are skipped")
    void sourceColumn() throws Exception {
        CsvJobRecordReader reader = reader("""
                title,company,source,jobid

                Data Analyst,Globex,board-a,A-17
                """, "partner-feed");

        RawJobRecord record = reader.read();
        assertThat(record.source()).isEqualTo("board-a");
        assertThat(record.sourceJobId()).isEqualTo("A-17");
        assertThat(reader.read()).isNull();
        reader.close();
    }
}
