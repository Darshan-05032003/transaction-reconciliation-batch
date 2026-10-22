package com.darshan.recon.matching;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class ReconcileJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ReconcileJobListener.class);

    private final ReconRunRepository reconRuns;
    private final Clock clock;

    public ReconcileJobListener(ReconRunRepository reconRuns, Clock clock) {
        this.reconRuns = reconRuns;
        this.clock = clock;
    }

    // A restarted execution inherits the run id from the failed one and resumes that run.
    @Override
    public void beforeJob(JobExecution jobExecution) {
        Long reconRunId = reconRunId(jobExecution);
        if (reconRunId != null) {
            reconRuns.markRunning(reconRunId);
            log.info("Restarting recon_run {} in job execution {}", reconRunId, jobExecution.getId());
        }
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Long reconRunId = reconRunId(jobExecution);
        if (reconRunId != null && jobExecution.getStatus() == BatchStatus.FAILED) {
            reconRuns.markFailed(reconRunId, OffsetDateTime.now(clock));
        }
        List<StepExecution> partitions = jobExecution.getStepExecutions().stream()
                .filter(step -> step.getStepName().startsWith(ReconcileJobKeys.PARTITION_STEP + ":"))
                .toList();
        log.info("reconcileJob finished statementFileId={} reconRunId={} status={} exitCode={} partitions={} "
                        + "pairsRead={} breaksWritten={} durationMs={}",
                jobExecution.getJobParameters().getLong(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER),
                reconRunId,
                jobExecution.getStatus(),
                jobExecution.getExitStatus().getExitCode(),
                partitions.size(),
                partitions.stream().mapToLong(StepExecution::getReadCount).sum(),
                partitions.stream().mapToLong(StepExecution::getWriteCount).sum(),
                durationMillis(jobExecution.getStartTime(), jobExecution.getEndTime()));
    }

    private static Long reconRunId(JobExecution jobExecution) {
        ExecutionContext context = jobExecution.getExecutionContext();
        return context.containsKey(ReconcileJobKeys.RECON_RUN_ID) ? context.getLong(ReconcileJobKeys.RECON_RUN_ID) : null;
    }

    private static Long durationMillis(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }
}
