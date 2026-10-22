package com.darshan.recon.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.ingest.file.MalformedRecordException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

class SkippedLineWriterTest {

    @TempDir
    Path folder;

    @Test
    void createsTheErrorFileWithAHeaderAndAppendsOneQuotedRowPerSkip() throws IOException {
        Path errorFile = folder.resolve("error").resolve("statement.csv.errors.csv");
        SkippedLineWriter writer = new SkippedLineWriter(errorFile);

        writer.onSkipInProcess(new RawStatementLine(6, "DTL,TXN-1,2026-10-02,12.5O,INR,UPI/Sunrise Bakery"),
                new MalformedRecordException("amount is not a decimal with at most two places: 12.5O"));
        writer.onSkipInProcess(new RawStatementLine(7, "DTL,TXN-2,2026-10-02,40.00,INR"),
                new MalformedRecordException("expected 6 fields but found 5"));

        List<String> lines = Files.readAllLines(errorFile);
        assertThat(lines).containsExactly(
                "line_number,reason,raw_line",
                "6,\"amount is not a decimal with at most two places: 12.5O\",\"DTL,TXN-1,2026-10-02,12.5O,INR,UPI/Sunrise Bakery\"",
                "7,\"expected 6 fields but found 5\",\"DTL,TXN-2,2026-10-02,40.00,INR\"");
    }

    @Test
    void recordsReadFailuresWithTheLineNumberReportedByTheReader() throws IOException {
        Path errorFile = folder.resolve("statement.csv.errors.csv");
        SkippedLineWriter writer = new SkippedLineWriter(errorFile);

        writer.onSkipInRead(new FlatFileParseException("Parsing error at line: 9", new RuntimeException("boom"),
                "DTL,\"quoted", 9));

        assertThat(Files.readAllLines(errorFile)).containsExactly(
                "line_number,reason,raw_line",
                "9,\"Parsing error at line: 9\",\"DTL,\"\"quoted\"");
    }

    @Test
    void appendsToAnExistingFileWithoutRepeatingTheHeader() throws IOException {
        Path errorFile = Files.writeString(folder.resolve("existing.errors.csv"), SkippedLineWriter.HEADER + "\n");

        new SkippedLineWriter(errorFile).onSkipInProcess(new RawStatementLine(2, "garbage"),
                new MalformedRecordException("expected a DTL record but found: garbage"));

        assertThat(Files.readAllLines(errorFile)).hasSize(2).first().isEqualTo(SkippedLineWriter.HEADER);
    }
}
