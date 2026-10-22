package com.darshan.recon.report;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

// Writes the run totals as metric,value rows: trivial to grep from a shell script and to
// load into a spreadsheet, and rewritten in full on a restart.
public class WriteRunSummaryTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(WriteRunSummaryTasklet.class);

    private final long reconRunId;
    private final Path summaryFile;
    private final RunSummaryRepository summaries;

    public WriteRunSummaryTasklet(long reconRunId, Path summaryFile, RunSummaryRepository summaries) {
        this.reconRunId = reconRunId;
        this.summaryFile = summaryFile;
        this.summaries = summaries;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        RunSummary summary = summaries.findByRunId(reconRunId)
                .orElseThrow(() -> new IllegalStateException("recon_run " + reconRunId + " does not exist"));
        try {
            Files.createDirectories(summaryFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(summaryFile, StandardCharsets.UTF_8)) {
                for (String line : lines(summary)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write run summary " + summaryFile, e);
        }
        log.info("Wrote summary of recon_run {} to {}", reconRunId, summaryFile);
        return RepeatStatus.FINISHED;
    }

    static List<String> lines(RunSummary summary) {
        List<String> lines = new ArrayList<>();
        lines.add("metric,value");
        lines.add(CsvFormat.line("recon_run_id", summary.reconRunId()));
        lines.add(CsvFormat.line("statement_file_id", summary.statementFileId()));
        lines.add(CsvFormat.line("file_name", summary.fileName()));
        lines.add(CsvFormat.line("account_number", summary.accountNumber()));
        lines.add(CsvFormat.line("statement_date", summary.statementDate()));
        lines.add(CsvFormat.line("status", summary.status()));
        lines.add(CsvFormat.line("matched_count", summary.matchedCount()));
        lines.add(CsvFormat.line("break_count", summary.breakCount()));
        lines.add(CsvFormat.line("match_rate_percent", summary.matchRatePercent()));
        summary.breaksByType().forEach((type, count) ->
                lines.add(CsvFormat.line("breaks_" + type.name().toLowerCase(Locale.ROOT), count)));
        return lines;
    }
}
