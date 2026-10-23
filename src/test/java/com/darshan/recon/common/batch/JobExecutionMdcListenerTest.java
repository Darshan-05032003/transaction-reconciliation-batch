package com.darshan.recon.common.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;

class JobExecutionMdcListenerTest {

    private final JobExecutionMdcListener listener = new JobExecutionMdcListener();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsTheJobNameAndExecutionIdIntoTheMdcForTheDurationOfTheJob() {
        JobExecution execution = new JobExecution(42L, new JobInstance(7L, "reconcileJob"), new JobParameters());

        listener.beforeJob(execution);

        assertThat(MDC.get(JobExecutionMdcListener.JOB_NAME)).isEqualTo("reconcileJob");
        assertThat(MDC.get(JobExecutionMdcListener.JOB_EXECUTION_ID)).isEqualTo("42");

        listener.afterJob(execution);

        assertThat(MDC.get(JobExecutionMdcListener.JOB_NAME)).isNull();
        assertThat(MDC.get(JobExecutionMdcListener.JOB_EXECUTION_ID)).isNull();
    }

    @Test
    void leavesOtherMdcEntriesAlone() {
        MDC.put("correlationId", "abc-123");
        JobExecution execution = new JobExecution(1L, new JobInstance(1L, "ingestStatementJob"), new JobParameters());

        listener.beforeJob(execution);
        listener.afterJob(execution);

        assertThat(MDC.get("correlationId")).isEqualTo("abc-123");
    }
}
