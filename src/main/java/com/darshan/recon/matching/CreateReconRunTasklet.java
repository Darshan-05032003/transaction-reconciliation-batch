package com.darshan.recon.matching;

import com.darshan.recon.ingest.StatementFileRecord;
import com.darshan.recon.ingest.StatementFileRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

public class CreateReconRunTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(CreateReconRunTasklet.class);

    private final long statementFileId;
    private final int dateToleranceDays;
    private final StatementFileRepository statementFiles;
    private final ReconRunRepository reconRuns;
    private final Clock clock;

    public CreateReconRunTasklet(long statementFileId, int dateToleranceDays, StatementFileRepository statementFiles,
                                 ReconRunRepository reconRuns, Clock clock) {
        this.statementFileId = statementFileId;
        this.dateToleranceDays = dateToleranceDays;
        this.statementFiles = statementFiles;
        this.reconRuns = reconRuns;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StatementFileRecord file = statementFiles.findById(statementFileId)
                .orElseThrow(() -> new IllegalStateException("statement_file " + statementFileId + " does not exist"));
        if (file.accountNumber() == null || file.statementDate() == null) {
            throw new IllegalStateException("statement_file " + statementFileId + " has no validated header");
        }
        JobExecution jobExecution = chunkContext.getStepContext().getStepExecution().getJobExecution();
        long reconRunId = reconRuns.insertRunning(statementFileId, jobExecution.getId(), OffsetDateTime.now(clock));
        ReconWindow window = ReconWindow.around(file.statementDate(), dateToleranceDays);

        ExecutionContext jobContext = jobExecution.getExecutionContext();
        jobContext.putLong(ReconcileJobKeys.RECON_RUN_ID, reconRunId);
        jobContext.putString(ReconcileJobKeys.ACCOUNT_NUMBER, file.accountNumber());
        jobContext.putString(ReconcileJobKeys.STATEMENT_DATE, file.statementDate().toString());
        jobContext.putString(ReconcileJobKeys.WINDOW_FROM, window.from().toString());
        jobContext.putString(ReconcileJobKeys.WINDOW_TO, window.to().toString());
        log.info("Started recon_run {} for statement_file {} ({}, account ending {}, ledger window {}..{})",
                reconRunId, statementFileId, file.fileName(), maskAccount(file.accountNumber()),
                window.from(), window.to());
        return RepeatStatus.FINISHED;
    }

    private static String maskAccount(String accountNumber) {
        return accountNumber.length() <= 4 ? accountNumber : accountNumber.substring(accountNumber.length() - 4);
    }
}
