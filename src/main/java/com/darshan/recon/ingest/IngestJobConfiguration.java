package com.darshan.recon.ingest;

import com.darshan.recon.common.batch.AnalyzeTableTasklet;
import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.common.observability.ReconMetrics;
import com.darshan.recon.ingest.file.MalformedRecordException;
import com.darshan.recon.ingest.file.StatementFileValidator;
import com.darshan.recon.ingest.file.StatementRecordFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.LimitCheckingExceptionHierarchySkipPolicy;
import org.springframework.batch.core.step.skip.NeverSkipItemSkipPolicy;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class IngestJobConfiguration {

    private static final Set<Class<? extends Throwable>> SKIPPABLE_EXCEPTIONS =
            Set.of(MalformedRecordException.class, FlatFileParseException.class);
    private static final int TRANSIENT_RETRY_LIMIT = 3;
    private static final String ERROR_FILE_SUFFIX = ".errors.csv";

    private static final String INSERT_STATEMENT_LINE = """
            insert into statement_line
                (statement_file_id, line_number, bank_reference, account_number, amount, currency, value_date, description)
            values
                (:statementFileId, :lineNumber, :bankReference, :accountNumber, :amount, :currency, :valueDate, :description)
            """;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ReconProperties properties;

    public IngestJobConfiguration(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                  ReconProperties properties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.properties = properties;
    }

    // Transitions are spelled out per exit code: a wildcard would also match FAILED and
    // carry a failed checksum step straight into validation.
    @Bean
    public Job ingestStatementJob(Step computeChecksumStep, Step validateControlTotalsStep,
                                  Step loadStatementLinesStep, Step analyzeStatementLinesStep,
                                  IngestJobListener ingestJobListener) {
        String completed = ExitStatus.COMPLETED.getExitCode();
        return new JobBuilder(IngestJobKeys.JOB_NAME, jobRepository)
                .validator(new StatementFilePathValidator())
                .listener(ingestJobListener)
                .start(computeChecksumStep)
                    .on(IngestJobKeys.EXIT_DUPLICATE).end(IngestJobKeys.EXIT_DUPLICATE)
                .from(computeChecksumStep).on(completed).to(validateControlTotalsStep)
                .from(validateControlTotalsStep).on(IngestJobKeys.EXIT_REJECTED).end(IngestJobKeys.EXIT_REJECTED)
                .from(validateControlTotalsStep).on(completed).to(loadStatementLinesStep)
                .from(loadStatementLinesStep).on(completed).to(analyzeStatementLinesStep)
                .end()
                .build();
    }

    @Bean
    public Step computeChecksumStep(ChecksumTasklet checksumTasklet) {
        return new StepBuilder(IngestJobKeys.CHECKSUM_STEP, jobRepository)
                .tasklet(checksumTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public ChecksumTasklet checksumTasklet(@Value("#{jobParameters['filePath']}") String filePath,
                                           StatementFileRepository statementFiles, Clock clock) {
        return new ChecksumTasklet(Path.of(filePath), statementFiles, clock);
    }

    @Bean
    public Step validateControlTotalsStep(ValidateFileTasklet validateFileTasklet) {
        return new StepBuilder(IngestJobKeys.VALIDATE_STEP, jobRepository)
                .tasklet(validateFileTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public ValidateFileTasklet validateFileTasklet(@Value("#{jobParameters['filePath']}") String filePath,
                                                   @Value("#{jobExecutionContext['statementFileId']}") Long statementFileId,
                                                   StatementFileValidator validator,
                                                   StatementFileRepository statementFiles, ReconMetrics metrics) {
        return new ValidateFileTasklet(Path.of(filePath), statementFileId, validator, statementFiles, metrics);
    }

    @Bean
    public Step loadStatementLinesStep(FlatFileItemReader<RawStatementLine> statementLineReader,
                                       StatementLineProcessor statementLineProcessor,
                                       JdbcBatchItemWriter<StatementLineRow> statementLineWriter,
                                       SkippedLineWriter skippedLineWriter) {
        return new StepBuilder(IngestJobKeys.LOAD_STEP, jobRepository)
                .<RawStatementLine, StatementLineRow>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(statementLineReader)
                .processor(statementLineProcessor)
                .writer(statementLineWriter)
                .faultTolerant()
                .skipPolicy(skipPolicy())
                .skipListener(skippedLineWriter)
                .retry(TransientDataAccessException.class, CannotGetJdbcConnectionException.class)
                .retryLimit(TRANSIENT_RETRY_LIMIT)
                .build();
    }

    // The reconcile job usually follows within seconds and its pairing query must be planned
    // with statistics that include the lines just loaded.
    @Bean
    public Step analyzeStatementLinesStep(JdbcTemplate jdbcTemplate) {
        return new StepBuilder(IngestJobKeys.ANALYZE_STEP, jobRepository)
                .tasklet(new AnalyzeTableTasklet(jdbcTemplate, "statement_line"), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<RawStatementLine> statementLineReader(
            @Value("#{jobParameters['filePath']}") String filePath) {
        return new FlatFileItemReaderBuilder<RawStatementLine>()
                .name("statementLineReader")
                .resource(new FileSystemResource(filePath))
                .encoding(StandardCharsets.UTF_8.name())
                .linesToSkip(1)
                .comments(StatementRecordFormat.TRAILER_TAG + StatementRecordFormat.SEPARATOR)
                .lineMapper((line, lineNumber) -> new RawStatementLine(lineNumber, line))
                .build();
    }

    @Bean
    @StepScope
    public StatementLineProcessor statementLineProcessor(
            @Value("#{jobExecutionContext['statementFileId']}") Long statementFileId,
            @Value("#{jobExecutionContext['accountNumber']}") String accountNumber) {
        return new StatementLineProcessor(statementFileId, accountNumber);
    }

    @Bean
    public JdbcBatchItemWriter<StatementLineRow> statementLineWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<StatementLineRow>()
                .dataSource(dataSource)
                .sql(INSERT_STATEMENT_LINE)
                .itemSqlParameterSourceProvider(row -> new MapSqlParameterSource()
                        .addValue("statementFileId", row.statementFileId())
                        .addValue("lineNumber", row.lineNumber())
                        .addValue("bankReference", row.bankReference())
                        .addValue("accountNumber", row.accountNumber())
                        .addValue("amount", row.amount())
                        .addValue("currency", row.currency())
                        .addValue("valueDate", row.valueDate())
                        .addValue("description", row.description()))
                .build();
    }

    @Bean
    @StepScope
    public SkippedLineWriter skippedLineWriter(@Value("#{jobParameters['filePath']}") String filePath) {
        String fileName = Path.of(filePath).getFileName().toString();
        return new SkippedLineWriter(properties.folders().error().resolve(fileName + ERROR_FILE_SUFFIX));
    }

    // The library's limit-checking policy refuses a limit of zero, which for us simply means
    // that no malformed line is tolerated.
    private SkipPolicy skipPolicy() {
        if (properties.skipLimit() == 0) {
            return new NeverSkipItemSkipPolicy();
        }
        return new LimitCheckingExceptionHierarchySkipPolicy(SKIPPABLE_EXCEPTIONS, properties.skipLimit());
    }
}
