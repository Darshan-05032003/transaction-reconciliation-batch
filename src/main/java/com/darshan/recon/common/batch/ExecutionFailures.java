package com.darshan.recon.common.batch;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.batch.core.job.JobExecution;

// One readable line for why an execution failed: the messages along the causal chain of
// the first step failure, without the stack trace that the exit description carries.
public final class ExecutionFailures {

    private ExecutionFailures() {
    }

    public static String reason(JobExecution jobExecution) {
        List<Throwable> stepFailures = jobExecution.getStepExecutions().stream()
                .flatMap(step -> step.getFailureExceptions().stream())
                .toList();
        List<Throwable> failures = stepFailures.isEmpty() ? jobExecution.getFailureExceptions() : stepFailures;
        return failures.stream()
                .findFirst()
                .map(ExecutionFailures::causalChainMessages)
                .orElse("job failed without an exception");
    }

    private static String causalChainMessages(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
                .map(Throwable::getMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(": "));
    }
}
