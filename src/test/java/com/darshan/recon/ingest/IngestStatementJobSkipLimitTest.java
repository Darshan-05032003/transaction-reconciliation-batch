package com.darshan.recon.ingest;

import static com.darshan.recon.ingest.IngestJobTestSupport.ingest;
import static com.darshan.recon.ingest.IngestJobTestSupport.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.common.config.ReconProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

// The skip limit is bound when the step is built, so a lower limit needs its own context.
@SpringBootTest
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.recon.skip-limit=2")
class IngestStatementJobSkipLimitTest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReconProperties properties;

    @TempDir
    Path inbox;

    @Test
    void exceedingTheSkipLimitFailsTheJobAndMarksTheStatementFileFailed() throws Exception {
        Path fixture = Path.of(getClass().getResource("/fixtures/statement_bad_lines.csv").toURI());
        Path file = Files.copy(fixture, inbox.resolve("statement_too_many_bad_lines.csv"));
        Path errorFile = properties.folders().error().resolve(file.getFileName() + ".errors.csv");
        Files.deleteIfExists(errorFile);

        JobExecution execution = ingest(jobOperatorTestUtils, file);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        StepExecution load = step(execution, IngestJobKeys.LOAD_STEP);
        assertThat(load.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(load.getSkipCount()).isEqualTo(2);
        assertThat(load.getRollbackCount()).isEqualTo(1);

        Map<String, Object> statementFile = jdbcTemplate.queryForMap(
                "select status, rejection_reason from statement_file where file_name = ?", file.getFileName().toString());
        assertThat(statementFile.get("status")).isEqualTo("FAILED");
        assertThat((String) statementFile.get("rejection_reason")).contains("Skip limit of '2' exceeded");

        Integer loaded = jdbcTemplate.queryForObject(
                "select count(*) from statement_line l join statement_file f on f.id = l.statement_file_id where f.file_name = ?",
                Integer.class, file.getFileName().toString());
        assertThat(loaded).isZero();
        assertThat(Files.readAllLines(errorFile)).hasSize(3);
    }
}
