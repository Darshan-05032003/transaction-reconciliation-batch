package com.darshan.recon.api;

import com.darshan.recon.common.batch.MdcTaskDecorator;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.springframework.batch.core.configuration.DuplicateJobException;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.JobOperatorFactoryBean;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

// The default JobOperator stays synchronous, which is what the scheduler, the scripts and
// the tests want. HTTP launches go through a second operator backed by a small bounded pool,
// so the request thread returns as soon as the job execution exists.
@Configuration(proxyBeanMethods = false)
public class ApiJobLaunchConfiguration {

    public static final String API_JOB_OPERATOR = "apiJobOperator";

    private static final int LAUNCH_THREADS = 2;
    private static final int LAUNCH_QUEUE_CAPACITY = 8;
    private static final int SHUTDOWN_WAIT_SECONDS = 60;

    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor apiJobLaunchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(LAUNCH_THREADS);
        executor.setMaxPoolSize(LAUNCH_THREADS);
        executor.setQueueCapacity(LAUNCH_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("recon-api-launch-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        return executor;
    }

    @Bean(name = API_JOB_OPERATOR, defaultCandidate = false)
    public JobOperator apiJobOperator(JobRepository jobRepository, List<Job> jobs,
                                      PlatformTransactionManager transactionManager,
                                      ObjectProvider<ObservationRegistry> observationRegistry,
                                      @Qualifier("apiJobLaunchExecutor") ThreadPoolTaskExecutor apiJobLaunchExecutor)
            throws Exception {
        MapJobRegistry jobRegistry = new MapJobRegistry();
        for (Job job : jobs) {
            try {
                jobRegistry.register(job);
            } catch (DuplicateJobException e) {
                throw new IllegalStateException("two jobs share the name " + job.getName(), e);
            }
        }
        JobOperatorFactoryBean factory = new JobOperatorFactoryBean();
        factory.setJobRepository(jobRepository);
        factory.setJobRegistry(jobRegistry);
        factory.setTransactionManager(transactionManager);
        factory.setTaskExecutor(apiJobLaunchExecutor);
        observationRegistry.ifAvailable(factory::setObservationRegistry);
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
