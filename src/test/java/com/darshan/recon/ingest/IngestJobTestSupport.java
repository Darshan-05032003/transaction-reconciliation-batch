package com.darshan.recon.ingest;

import java.nio.file.Path;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;

// Lives outside the test classes on purpose: @SpringBatchTest treats any test method that
// returns a JobExecution or a StepExecution as a factory for a fake scoped execution.
final class IngestJobTestSupport {

    private IngestJobTestSupport() {
    }

    static JobExecution ingest(JobOperatorTestUtils jobOperatorTestUtils, Path file) throws Exception {
        return jobOperatorTestUtils.startJob(new JobParametersBuilder()
                .addString(IngestJobKeys.FILE_PATH_PARAMETER, file.toString())
                .toJobParameters());
    }

    static StepExecution step(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> stepName.equals(step.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("step " + stepName + " did not run"));
    }
}
