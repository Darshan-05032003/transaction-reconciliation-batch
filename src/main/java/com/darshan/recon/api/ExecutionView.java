package com.darshan.recon.api;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;

public record ExecutionView(
        long executionId,
        String jobName,
        long jobInstanceId,
        Map<String, String> parameters,
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
                execution.getJobInstance().getId(), parameters, execution.getStatus(),
                execution.getExitStatus().getExitCode(), truncate(execution.getExitStatus().getExitDescription()),
                execution.getCreateTime(), execution.getStartTime(), execution.getEndTime(),
                durationMillis(execution.getStartTime(), execution.getEndTime()), steps);
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
