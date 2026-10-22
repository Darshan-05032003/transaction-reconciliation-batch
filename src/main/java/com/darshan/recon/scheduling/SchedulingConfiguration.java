package com.darshan.recon.scheduling;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.ingest.IngestJobKeys;
import com.darshan.recon.matching.ReconcileJobKeys;
import java.time.Clock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {

    // The default JobOperator is the synchronous one: the poller must know how ingest ended
    // before it reconciles, and how reconciliation ended before it moves the file.
    @Bean
    public InboxPoller inboxPoller(ReconProperties properties, JobOperator jobOperator,
                                   @Qualifier(IngestJobKeys.JOB_NAME) Job ingestJob,
                                   @Qualifier(ReconcileJobKeys.JOB_NAME) Job reconcileJob, Clock clock) {
        return new InboxPoller(properties.folders(), jobOperator, ingestJob, reconcileJob, clock);
    }
}
