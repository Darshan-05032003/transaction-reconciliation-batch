package com.darshan.recon.matching;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.ingest.StatementFileRepository;
import com.darshan.recon.matching.engine.CandidatePair;
import com.darshan.recon.matching.engine.MatchingEngine;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class ReconcileJobConfiguration {

    private static final int TRANSIENT_RETRY_LIMIT = 3;
    private static final int EXECUTOR_SHUTDOWN_WAIT_SECONDS = 60;

    private static final String INSERT_RECON_BREAK = """
            insert into recon_break
                (recon_run_id, break_type, severity, ledger_txn_id, statement_line_id, ledger_amount, bank_amount, details)
            values
                (:reconRunId, :breakType, :severity, :ledgerTxnId, :statementLineId, :ledgerAmount, :bankAmount, :details)
            """;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ReconProperties properties;

    public ReconcileJobConfiguration(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                     ReconProperties properties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.properties = properties;
    }

    @Bean
    public Job reconcileJob(Step createReconRunStep, Step matchCandidatesStep, Step finaliseReconRunStep,
                            Step writeBreakReportStep, Step writeRunSummaryStep,
                            ReconcileJobListener reconcileJobListener,
                            ReconcileJobParametersValidator reconcileJobParametersValidator) {
        return new JobBuilder(ReconcileJobKeys.JOB_NAME, jobRepository)
                .validator(reconcileJobParametersValidator)
                .listener(reconcileJobListener)
                .start(createReconRunStep)
                .next(matchCandidatesStep)
                .next(finaliseReconRunStep)
                .next(writeBreakReportStep)
                .next(writeRunSummaryStep)
                .build();
    }

    @Bean
    public Step createReconRunStep(CreateReconRunTasklet createReconRunTasklet) {
        return new StepBuilder(ReconcileJobKeys.CREATE_RUN_STEP, jobRepository)
                .tasklet(createReconRunTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public CreateReconRunTasklet createReconRunTasklet(
            @Value("#{jobParameters['statementFileId']}") Long statementFileId,
            StatementFileRepository statementFiles, ReconRunRepository reconRuns, Clock clock) {
        return new CreateReconRunTasklet(statementFileId, properties.dateToleranceDays(), statementFiles, reconRuns, clock);
    }

    @Bean
    public Step matchCandidatesStep(Step matchPartitionStep, TaskExecutorPartitionHandler matchPartitionHandler) {
        return new StepBuilder(ReconcileJobKeys.MATCH_STEP, jobRepository)
                .partitioner(ReconcileJobKeys.PARTITION_STEP, new ReferenceHashPartitioner())
                .step(matchPartitionStep)
                .partitionHandler(matchPartitionHandler)
                .build();
    }

    // Step-scoped so that a run can ask for a different number of partitions than the
    // configured default; the thread pool stays at the configured size either way.
    @Bean
    @StepScope
    public TaskExecutorPartitionHandler matchPartitionHandler(
            @Value("#{jobParameters['gridSize']}") Long gridSize, Step matchPartitionStep,
            @Qualifier("matchTaskExecutor") ThreadPoolTaskExecutor matchTaskExecutor) throws Exception {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(matchPartitionStep);
        handler.setTaskExecutor(matchTaskExecutor);
        handler.setGridSize(gridSize == null ? properties.gridSize() : gridSize.intValue());
        handler.afterPropertiesSet();
        return handler;
    }

    // Not a default candidate, so it is never picked up for @Async or the Batch job operator.
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor matchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.gridSize());
        executor.setMaxPoolSize(properties.gridSize());
        executor.setThreadNamePrefix("recon-match-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(EXECUTOR_SHUTDOWN_WAIT_SECONDS);
        return executor;
    }

    @Bean
    public Step matchPartitionStep(JdbcCursorItemReader<CandidatePair> candidatePairReader,
                                   MatchingItemProcessor matchingItemProcessor,
                                   JdbcBatchItemWriter<ReconBreakRow> reconBreakWriter) {
        return new StepBuilder(ReconcileJobKeys.PARTITION_STEP, jobRepository)
                .<CandidatePair, ReconBreakRow>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(candidatePairReader)
                .processor(matchingItemProcessor)
                .writer(reconBreakWriter)
                .faultTolerant()
                .retry(TransientDataAccessException.class, CannotGetJdbcConnectionException.class)
                .retryLimit(TRANSIENT_RETRY_LIMIT)
                .build();
    }

    // PostgreSQL only streams a cursor when autocommit is off; with it on, the driver would
    // fetch the whole result set into memory regardless of the fetch size.
    @Bean
    @StepScope
    public JdbcCursorItemReader<CandidatePair> candidatePairReader(
            @Value("#{jobParameters['statementFileId']}") Long statementFileId,
            @Value("#{jobExecutionContext['accountNumber']}") String accountNumber,
            @Value("#{jobExecutionContext['windowFrom']}") String windowFrom,
            @Value("#{jobExecutionContext['windowTo']}") String windowTo,
            @Value("#{stepExecutionContext['partitionIndex']}") Integer partitionIndex,
            @Value("#{stepExecutionContext['partitionCount']}") Integer partitionCount,
            DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<CandidatePair>()
                .name("candidatePairReader")
                .dataSource(dataSource)
                .sql(CandidatePairQuery.PAIRING_SQL)
                .preparedStatementSetter(CandidatePairQuery.pairingParameters(statementFileId, accountNumber,
                        ReconWindow.parse(windowFrom, windowTo), partitionCount, partitionIndex))
                .rowMapper(new CandidatePairRowMapper())
                .fetchSize(properties.chunkSize())
                .connectionAutoCommit(false)
                .build();
    }

    @Bean
    public MatchingEngine matchingEngine() {
        return MatchingEngine.standard(properties.amountTolerance(), properties.dateToleranceDays());
    }

    @Bean
    @StepScope
    public MatchingItemProcessor matchingItemProcessor(@Value("#{jobExecutionContext['reconRunId']}") Long reconRunId,
                                                       MatchingEngine matchingEngine) {
        return new MatchingItemProcessor(matchingEngine, reconRunId);
    }

    @Bean
    public JdbcBatchItemWriter<ReconBreakRow> reconBreakWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<ReconBreakRow>()
                .dataSource(dataSource)
                .sql(INSERT_RECON_BREAK)
                .itemSqlParameterSourceProvider(row -> new MapSqlParameterSource()
                        .addValue("reconRunId", row.reconRunId())
                        .addValue("breakType", row.breakType().name())
                        .addValue("severity", row.severity().name())
                        .addValue("ledgerTxnId", row.ledgerTxnId())
                        .addValue("statementLineId", row.statementLineId())
                        .addValue("ledgerAmount", row.ledgerAmount())
                        .addValue("bankAmount", row.bankAmount())
                        .addValue("details", row.details()))
                .build();
    }

    @Bean
    public Step finaliseReconRunStep(FinaliseReconRunTasklet finaliseReconRunTasklet) {
        return new StepBuilder(ReconcileJobKeys.FINALISE_STEP, jobRepository)
                .tasklet(finaliseReconRunTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public FinaliseReconRunTasklet finaliseReconRunTasklet(
            @Value("#{jobParameters['statementFileId']}") Long statementFileId,
            @Value("#{jobExecutionContext['reconRunId']}") Long reconRunId,
            @Value("#{jobExecutionContext['accountNumber']}") String accountNumber,
            @Value("#{jobExecutionContext['windowFrom']}") String windowFrom,
            @Value("#{jobExecutionContext['windowTo']}") String windowTo,
            ReconRunRepository reconRuns, StatementFileRepository statementFiles, Clock clock) {
        return new FinaliseReconRunTasklet(reconRunId, statementFileId, accountNumber,
                ReconWindow.parse(windowFrom, windowTo), reconRuns, statementFiles, clock);
    }
}
