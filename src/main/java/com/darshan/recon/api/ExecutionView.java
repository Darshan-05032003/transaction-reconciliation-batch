package com.darshan.recon.api;

import com.darshan.recon.ingest.IngestJobKeys;
import com.darshan.recon.matching.ReconcileJobKeys;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

// statementFileId and reconRunId are what an execution produced or worked on, so that a
// script can chain ingest, reconcile and the run summary without querying the database.
public record ExecutionView(
        long executionId,
        String jobName,
        long jobInstanceId,
        Map<String, String> parameters,
        Long statementFileId,
        Long reconRunId,
        BatchStatus status,
        String exitCode,
        String exitDescription,
        LocalDateTime createTime,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationMs,
        List<StepView> steps) {

    private static final int MAX_EXIT_DESCRIPTION_LENGTH = 500;

    public record StepView(
            long stepExecutionId,
            String name,
            BatchStatus status,
            String exitCode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            long readCount,
            long writeCount,
            long filterCount,
            long skipCount,
            long readSkipCount,
            long processSkipCount,
            long writeSkipCount,
            long commitCount,
            long rollbackCount) {

        static StepView of(StepExecution step) {
            return new StepView(step.getId(), step.getStepName(), step.getStatus(), step.getExitStatus().getExitCode(),
                    step.getStartTime(), step.getEndTime(), step.getReadCount(), step.getWriteCount(),
                    step.getFilterCount(), step.getSkipCount(), step.getReadSkipCount(), step.getProcessSkipCount(),
                    step.getWriteSkipCount(), step.getCommitCount(), step.getRollbackCount());
        }
    }

    static ExecutionView of(JobExecution execution) {
        Map<String, String> parameters = new TreeMap<>();
        execution.getJobParameters()
                .forEach(parameter -> parameters.put(parameter.name(), String.valueOf(parameter.value())));
        List<StepView> steps = execution.getStepExecutions().stream()
                .sorted(Comparator.comparing(StepExecution::getId))
                .map(StepView::of)
                .toList();
        return new ExecutionView(execution.getId(), execution.getJobInstance().getJobName(),
                execution.getJobInstance().getId(), parameters, statementFileId(execution), reconRunId(execution),
                execution.getStatus(),
                execution.getExitStatus().getExitCode(), truncate(execution.getExitStatus().getExitDescription()),
                execution.getCreateTime(), execution.getStartTime(), execution.getEndTime(),
                durationMillis(execution.getStartTime(), execution.getEndTime()), steps);
    }

    // The ingest job records the file it registered in its execution context; the reconcile
    // job receives the file as a parameter.
    private static Long statementFileId(JobExecution execution) {
        ExecutionContext context = execution.getExecutionContext();
        if (context.containsKey(IngestJobKeys.STATEMENT_FILE_ID)) {
            return context.getLong(IngestJobKeys.STATEMENT_FILE_ID);
        }
        return execution.getJobParameters().getLong(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER);
    }

    private static Long reconRunId(JobExecution execution) {
        ExecutionContext context = execution.getExecutionContext();
        return context.containsKey(ReconcileJobKeys.RECON_RUN_ID) ? context.getLong(ReconcileJobKeys.RECON_RUN_ID) : null;
    }

    private static Long durationMillis(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }

    private static String truncate(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.length() <= MAX_EXIT_DESCRIPTION_LENGTH
                ? description
                : description.substring(0, MAX_EXIT_DESCRIPTION_LENGTH);
    }
}
