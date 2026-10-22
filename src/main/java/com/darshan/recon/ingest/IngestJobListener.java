package com.darshan.recon.ingest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class IngestJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(IngestJobListener.class);

    private final StatementFileRepository statementFiles;

    public IngestJobListener(StatementFileRepository statementFiles) {
        this.statementFiles = statementFiles;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        ExecutionContext context = jobExecution.getExecutionContext();
        Long statementFileId = context.containsKey(IngestJobKeys.STATEMENT_FILE_ID)
                ? context.getLong(IngestJobKeys.STATEMENT_FILE_ID)
                : null;
        if (statementFileId != null) {
            updateStatus(statementFileId, jobExecution);
        }
        logSummary(statementFileId, jobExecution);
    }

    // A rejected file was already marked by the validation step, and a stopped job keeps
    // RECEIVED so that a restart can finish it. A duplicate never gets a row of its own.
    private void updateStatus(long statementFileId, JobExecution jobExecution) {
        boolean completedNormally = jobExecution.getStatus() == BatchStatus.COMPLETED
                && ExitStatus.COMPLETED.getExitCode().equals(jobExecution.getExitStatus().getExitCode());
        if (completedNormally) {
            statementFiles.markValidated(statementFileId);
        } else if (jobExecution.getStatus() == BatchStatus.FAILED) {
            statementFiles.markFailed(statementFileId, failureReason(jobExecution));
        }
    }

    private static String failureReason(JobExecution jobExecution) {
        List<Throwable> stepFailures = jobExecution.getStepExecutions().stream()
                .flatMap(step -> step.getFailureExceptions().stream())
                .toList();
        List<Throwable> failures = stepFailures.isEmpty() ? jobExecution.getFailureExceptions() : stepFailures;
        return failures.stream()
                .findFirst()
                .map(IngestJobListener::causalChainMessages)
                .orElse("job failed without an exception");
    }

    private static String causalChainMessages(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
                .map(Throwable::getMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(": "));
    }

    private static void logSummary(Long statementFileId, JobExecution jobExecution) {
        Optional<StepExecution> loadStep = jobExecution.getStepExecutions().stream()
                .filter(step -> IngestJobKeys.LOAD_STEP.equals(step.getStepName()))
                .findFirst();
        ExecutionContext context = jobExecution.getExecutionContext();
        String filePath = jobExecution.getJobParameters().getString(IngestJobKeys.FILE_PATH_PARAMETER);
        log.info("ingestStatementJob finished file={} statementFileId={} status={} exitCode={} declared={} read={} "
                        + "written={} skipped={} durationMs={}",
                filePath == null ? null : Path.of(filePath).getFileName(),
                statementFileId,
                jobExecution.getStatus(),
                jobExecution.getExitStatus().getExitCode(),
                context.containsKey(IngestJobKeys.DECLARED_COUNT) ? context.getInt(IngestJobKeys.DECLARED_COUNT) : null,
                loadStep.map(StepExecution::getReadCount).orElse(0L),
                loadStep.map(StepExecution::getWriteCount).orElse(0L),
                loadStep.map(StepExecution::getSkipCount).orElse(0L),
                durationMillis(jobExecution.getStartTime(), jobExecution.getEndTime()));
    }

    private static Long durationMillis(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }
}
