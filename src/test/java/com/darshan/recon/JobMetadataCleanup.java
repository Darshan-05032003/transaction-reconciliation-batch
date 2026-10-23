package com.darshan.recon;

import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

// Spring Batch 6 creates the job instance before the parameters are validated, and
// JobRepositoryTestUtils.removeJobExecutions() only deletes instances that have an execution,
// so every refused launch leaves an instance behind. Test classes that share a context and a
// database reset the metadata through here, so what one class provokes never reaches the next.
public final class JobMetadataCleanup {

    private JobMetadataCleanup() {
    }

    public static void removeAllJobs(JobRepositoryTestUtils jobRepositoryTestUtils, JdbcTemplate jdbcTemplate) {
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("delete from batch_job_instance");
    }
}
