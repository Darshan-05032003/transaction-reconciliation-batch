package com.darshan.recon.report;

import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.matching.ReconcileJobKeys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

// The two reporting steps appended to reconcileJob: the break report streams every break of
// the run from a cursor into a CSV file, and the summary tasklet writes the run totals.
@Configuration(proxyBeanMethods = false)
public class BreakReportConfiguration {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ReconProperties properties;

    public BreakReportConfiguration(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                    ReconProperties properties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.properties = properties;
    }

    @Bean
    public Step writeBreakReportStep(JdbcCursorItemReader<BreakReportRow> breakReportReader,
                                     FlatFileItemWriter<BreakReportRow> breakReportWriter) {
        return new StepBuilder(ReconcileJobKeys.REPORT_STEP, jobRepository)
                .<BreakReportRow, BreakReportRow>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(breakReportReader)
                .writer(breakReportWriter)
                .build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<BreakReportRow> breakReportReader(
            @Value("#{jobExecutionContext['reconRunId']}") Long reconRunId, DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<BreakReportRow>()
                .name("breakReportReader")
                .dataSource(dataSource)
                .sql(BreakReportQuery.SQL)
                .queryArguments(reconRunId)
                .rowMapper(BreakReportQuery.ROW_MAPPER)
                .fetchSize(properties.chunkSize())
                .connectionAutoCommit(false)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemWriter<BreakReportRow> breakReportWriter(
            @Value("#{jobExecutionContext['reconRunId']}") Long reconRunId,
            @Value("#{jobExecutionContext['statementDate']}") String statementDate) {
        return new FlatFileItemWriterBuilder<BreakReportRow>()
                .name("breakReportWriter")
                .resource(new FileSystemResource(ReportFileNames.breakReport(
                        properties.folders().reports(), LocalDate.parse(statementDate), reconRunId)))
                .encoding(StandardCharsets.UTF_8.name())
                .headerCallback(writer -> writer.write(BreakReportRow.HEADER))
                .lineAggregator(BreakReportRow::toCsvLine)
                .build();
    }

    @Bean
    public Step writeRunSummaryStep(WriteRunSummaryTasklet writeRunSummaryTasklet) {
        return new StepBuilder(ReconcileJobKeys.SUMMARY_STEP, jobRepository)
                .tasklet(writeRunSummaryTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public WriteRunSummaryTasklet writeRunSummaryTasklet(
            @Value("#{jobExecutionContext['reconRunId']}") Long reconRunId,
            @Value("#{jobExecutionContext['statementDate']}") String statementDate,
            RunSummaryRepository summaries) {
        return new WriteRunSummaryTasklet(reconRunId, ReportFileNames.runSummary(
                properties.folders().reports(), LocalDate.parse(statementDate), reconRunId), summaries);
    }
}
