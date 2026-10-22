package com.darshan.recon.ingest;

import static com.darshan.recon.ingest.IngestJobTestSupport.ingest;
import static com.darshan.recon.ingest.IngestJobTestSupport.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.testdata.GenerationManifest;
import com.darshan.recon.testdata.GenerationRequest;
import com.darshan.recon.testdata.TestDataGenerator;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class IngestStatementJobTest {

    private static final String CLEAN_FIXTURE_SHA256 =
            "b567d6518a78817c49ffca4b0045f2c59efb396f1d5ff13f0f9dce6dec863d03";

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    @Qualifier("ingestStatementJob")
    private Job ingestStatementJob;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReconProperties properties;

    @Autowired
    private TestDataGenerator generator;

    @TempDir
    Path inbox;

    @BeforeEach
    void startFromAnEmptyState() {
        jobOperatorTestUtils.setJob(ingestStatementJob);
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("delete from statement_line");
        jdbcTemplate.update("delete from statement_file");
    }

    @Test
    void cleanFileLoadsEveryLineAndMarksTheStatementFileValidated() throws Exception {
        Path file = copyFixture("statement_clean.csv");

        JobExecution execution = ingest(jobOperatorTestUtils, file);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly(IngestJobKeys.CHECKSUM_STEP, IngestJobKeys.VALIDATE_STEP, IngestJobKeys.LOAD_STEP);

        StepExecution load = step(execution, IngestJobKeys.LOAD_STEP);
        assertThat(load.getReadCount()).isEqualTo(5);
        assertThat(load.getWriteCount()).isEqualTo(5);
        assertThat(load.getSkipCount()).isZero();

        Map<String, Object> statementFile = singleStatementFile();
        assertThat(statementFile.get("status")).isEqualTo("VALIDATED");
        assertThat(statementFile.get("file_name")).isEqualTo("statement_clean.csv");
        assertThat(statementFile.get("checksum_sha256")).isEqualTo(CLEAN_FIXTURE_SHA256);
        assertThat(statementFile.get("bank_code")).isEqualTo("BANK01");
        assertThat(statementFile.get("account_number")).isEqualTo("000123456789");
        assertThat(statementFile.get("statement_date")).isEqualTo(Date.valueOf(LocalDate.of(2026, 10, 1)));
        assertThat(statementFile.get("declared_count")).isEqualTo(5);
        assertThat((BigDecimal) statementFile.get("declared_total")).isEqualByComparingTo("20915.74");
        assertThat(statementFile.get("rejection_reason")).isNull();

        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "select * from statement_line order by line_number");
        assertThat(lines).hasSize(5);
        assertThat(lines).extracting(line -> line.get("line_number")).containsExactly(2, 3, 4, 5, 6);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.get("statement_file_id")).isEqualTo(statementFile.get("id"));
            assertThat(line.get("account_number")).isEqualTo("000123456789");
            assertThat(line.get("currency")).isEqualTo("INR");
        });
        Map<String, Object> reversal = lines.get(2);
        assertThat(reversal.get("bank_reference")).isEqualTo("TXN20261001-000123456789-0000003");
        assertThat((BigDecimal) reversal.get("amount")).isEqualByComparingTo("-450.00");
        assertThat(reversal.get("value_date")).isEqualTo(Date.valueOf(LocalDate.of(2026, 10, 1)));
        assertThat(reversal.get("description")).isEqualTo("CHQ/Anand Pharma");
    }

    @Test
    void malformedLinesAreSkippedAndWrittenToTheErrorFileWithReasons() throws Exception {
        Path file = copyFixture("statement_bad_lines.csv");
        Path errorFile = errorFileFor(file);
        Files.deleteIfExists(errorFile);

        JobExecution execution = ingest(jobOperatorTestUtils, file);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution load = step(execution, IngestJobKeys.LOAD_STEP);
        assertThat(load.getReadCount()).isEqualTo(7);
        assertThat(load.getWriteCount()).isEqualTo(4);
        assertThat(load.getProcessSkipCount()).isEqualTo(3);
        assertThat(load.getSkipCount()).isEqualTo(3);

        assertThat(countLines()).isEqualTo(4);
        Map<String, Object> statementFile = singleStatementFile();
        assertThat(statementFile.get("status")).isEqualTo("VALIDATED");
        assertThat(statementFile.get("declared_count")).isEqualTo(7);
        assertThat((BigDecimal) statementFile.get("declared_total")).isEqualByComparingTo("1620.85");

        List<String> errorRows = Files.readAllLines(errorFile);
        assertThat(errorRows).hasSize(4);
        assertThat(errorRows.getFirst()).isEqualTo(SkippedLineWriter.HEADER);
        assertThat(errorRows.get(1)).startsWith("6,\"amount is not a decimal")
                .endsWith("\"DTL,TXN20261002-000123456789-0000005,2026-10-02,12.5O,INR,UPI/Sunrise Bakery\"");
        assertThat(errorRows.get(2)).startsWith("7,\"expected 6 fields but found 5\"");
        assertThat(errorRows.get(3)).startsWith("8,\"valueDate is not an ISO date");
    }

    @Test
    void wrongTrailerTotalsRejectTheFileBeforeAnyLineIsLoaded() throws Exception {
        Path file = copyFixture("statement_wrong_trailer.csv");

        JobExecution execution = ingest(jobOperatorTestUtils, file);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(IngestJobKeys.EXIT_REJECTED);
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly(IngestJobKeys.CHECKSUM_STEP, IngestJobKeys.VALIDATE_STEP);

        Map<String, Object> statementFile = singleStatementFile();
        assertThat(statementFile.get("status")).isEqualTo("REJECTED");
        assertThat(statementFile.get("rejection_reason")).isEqualTo(
                "trailer declares 3 records totalling 650.00 but the file contains 3 records totalling 600.00");
        assertThat(countLines()).isZero();
    }

    @Test
    void sameContentTwiceEndsWithDuplicateAndChangesNothing() throws Exception {
        Path original = copyFixture("statement_clean.csv");
        Path resent = Files.copy(original, inbox.resolve("statement_clean_resent.csv"));

        JobExecution first = ingest(jobOperatorTestUtils, original);
        JobExecution second = ingest(jobOperatorTestUtils, resent);

        assertThat(first.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getExitStatus().getExitCode()).isEqualTo(IngestJobKeys.EXIT_DUPLICATE);
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly(IngestJobKeys.CHECKSUM_STEP);
        assertThat(step(second, IngestJobKeys.CHECKSUM_STEP).getExitStatus().getExitDescription())
                .isEqualTo("same content as statement_file " + singleStatementFile().get("id"));
        assertThat(countFiles()).isEqualTo(1);
        assertThat(countLines()).isEqualTo(5);
    }

    @Test
    void launchingTheSamePathAgainIsRefusedByTheJobRepository() throws Exception {
        Path file = copyFixture("statement_clean.csv");
        ingest(jobOperatorTestUtils, file);

        assertThatThrownBy(() -> ingest(jobOperatorTestUtils, file)).isInstanceOf(JobInstanceAlreadyCompleteException.class);
        assertThat(countFiles()).isEqualTo(1);
    }

    @Test
    void jobWithoutAReadableFilePathIsRejectedBeforeItStarts() {
        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(new JobParameters()))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("filePath");
        assertThatThrownBy(() -> ingest(jobOperatorTestUtils, inbox.resolve("missing.csv")))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("does not exist");
        assertThat(countFiles()).isZero();
    }

    @Test
    void generatedFileLoadsAndSkipsExactlyWhatTheManifestDeclares() throws Exception {
        GenerationManifest manifest = generator.generate(
                new GenerationRequest(LocalDate.of(2026, 10, 1), 1000, 42L), inbox);
        Path file = inbox.resolve(manifest.fileName());
        Path errorFile = errorFileFor(file);
        Files.deleteIfExists(errorFile);
        assertThat(manifest.expectedSkippedLines()).isPositive().isLessThanOrEqualTo(properties.skipLimit());

        JobExecution execution = ingest(jobOperatorTestUtils, file);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution load = step(execution, IngestJobKeys.LOAD_STEP);
        assertThat(load.getReadCount()).isEqualTo(manifest.declaredCount());
        assertThat(load.getWriteCount()).isEqualTo(manifest.expectedLoadedLines());
        assertThat(load.getSkipCount()).isEqualTo(manifest.expectedSkippedLines());
        assertThat(load.getCommitCount()).isGreaterThan(1);

        assertThat(countLines()).isEqualTo(manifest.expectedLoadedLines());
        assertThat(Files.readAllLines(errorFile)).hasSize(manifest.expectedSkippedLines() + 1);

        Map<String, Object> statementFile = singleStatementFile();
        assertThat(statementFile.get("status")).isEqualTo("VALIDATED");
        assertThat(statementFile.get("account_number")).isEqualTo(manifest.accountNumber());
        assertThat(statementFile.get("declared_count")).isEqualTo(manifest.declaredCount());
        assertThat((BigDecimal) statementFile.get("declared_total")).isEqualByComparingTo(manifest.declaredTotal());
    }

    private Path copyFixture(String name) throws IOException, URISyntaxException {
        Path fixture = Path.of(getClass().getResource("/fixtures/" + name).toURI());
        return Files.copy(fixture, inbox.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path errorFileFor(Path file) {
        return properties.folders().error().resolve(file.getFileName() + ".errors.csv");
    }


    private Map<String, Object> singleStatementFile() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from statement_file");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private int countFiles() {
        return jdbcTemplate.queryForObject("select count(*) from statement_file", Integer.class);
    }

    private int countLines() {
        return jdbcTemplate.queryForObject("select count(*) from statement_line", Integer.class);
    }
}
