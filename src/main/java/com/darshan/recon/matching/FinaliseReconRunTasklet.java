package com.darshan.recon.matching;

import com.darshan.recon.common.observability.ReconMetrics;
import com.darshan.recon.ingest.StatementFileRepository;
import com.darshan.recon.report.RunSummary;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

// Counts come from the business tables, not from step counters: after a restart the
// counters are spread over several executions, while the breaks and the pairing set are not.
public class FinaliseReconRunTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(FinaliseReconRunTasklet.class);

    private final long reconRunId;
    private final long statementFileId;
    private final String accountNumber;
    private final ReconWindow window;
    private final ReconRunRepository reconRuns;
    private final StatementFileRepository statementFiles;
    private final ReconMetrics metrics;
    private final Clock clock;

    public FinaliseReconRunTasklet(long reconRunId, long statementFileId, String accountNumber, ReconWindow window,
                                   ReconRunRepository reconRuns, StatementFileRepository statementFiles,
                                   ReconMetrics metrics, Clock clock) {
        this.reconRunId = reconRunId;
        this.statementFileId = statementFileId;
        this.accountNumber = accountNumber;
        this.window = window;
        this.reconRuns = reconRuns;
        this.statementFiles = statementFiles;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        int breaks = reconRuns.countBreaks(reconRunId);
        int candidates = reconRuns.countCandidates(statementFileId, accountNumber, window);
        int matched = candidates - breaks;
        if (matched < 0) {
            throw new IllegalStateException("recon_run %d has %d breaks for %d candidate pairs"
                    .formatted(reconRunId, breaks, candidates));
        }
        reconRuns.complete(reconRunId, matched, breaks, OffsetDateTime.now(clock));
        statementFiles.markReconciled(statementFileId);
        metrics.runCompleted(RunSummary.matchRate(matched, breaks));
        log.info("recon_run {} completed for statement_file {}: candidates={} matched={} breaks={}",
                reconRunId, statementFileId, candidates, matched, breaks);
        return RepeatStatus.FINISHED;
    }
}
