package com.darshan.recon.common.batch;

import org.slf4j.MDC;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

// Registered first on every job: listeners run beforeJob in order and afterJob in reverse,
// so the job name and execution id are in the MDC for everything the other listeners log,
// including their summary lines.
@Component
public class JobExecutionMdcListener implements JobExecutionListener {

    public static final String JOB_NAME = "jobName";
    public static final String JOB_EXECUTION_ID = "jobExecutionId";

    @Override
    public void beforeJob(JobExecution jobExecution) {
        MDC.put(JOB_NAME, jobExecution.getJobInstance().getJobName());
        MDC.put(JOB_EXECUTION_ID, String.valueOf(jobExecution.getId()));
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        MDC.remove(JOB_NAME);
        MDC.remove(JOB_EXECUTION_ID);
    }
}
