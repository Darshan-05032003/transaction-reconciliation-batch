package com.darshan.recon.api;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;

public record LaunchResponse(long executionId, String jobName, BatchStatus status) {

    static LaunchResponse of(JobExecution execution) {
        return new LaunchResponse(execution.getId(), execution.getJobInstance().getJobName(), execution.getStatus());
    }
}
