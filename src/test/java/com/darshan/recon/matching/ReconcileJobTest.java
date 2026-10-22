package com.darshan.recon.matching;

import static com.darshan.recon.matching.ReconcileJobTestSupport.breakCountsByType;
import static com.darshan.recon.matching.ReconcileJobTestSupport.canonicalBreaks;
import static com.darshan.recon.matching.ReconcileJobTestSupport.ingest;
import static com.darshan.recon.matching.ReconcileJobTestSupport.partitionSteps;
import static com.darshan.recon.matching.ReconcileJobTestSupport.reconRun;
import static com.darshan.recon.matching.ReconcileJobTestSupport.reconcile;
import static com.darshan.recon.matching.ReconcileJobTestSupport.reconcileParameters;
import static com.darshan.recon.matching.ReconcileJobTestSupport.rerunParameters;
import static com.darshan.recon.matching.ReconcileJobTestSupport.singleReconRunId;
import static com.darshan.recon.matching.ReconcileJobTestSupport.statementFileId;
import static com.darshan.recon.matching.ReconcileJobTestSupport.statementFileStatus;
import static com.darshan.recon.matching.ReconcileJobTestSupport.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.testdata.GenerationManifest;
import com.darshan.recon.testdata.GenerationManifest.ExpectedBreaks;
import com.darshan.recon.testdata.GenerationRequest;
import com.darshan.recon.testdata.TestDataGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
class ReconcileJobTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("ingestStatementJob")
    private Job ingestJob;

    @Autowired
    @Qualifier("reconcileJob")
    private Job reconcileJob;

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
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("delete from recon_break");
        jdbcTemplate.update("delete from recon_run");
        jdbcTemplate.update("delete from statement_line");
        jdbcTemplate.update("delete from statement_file");
    }

    @Test
    void generatedFileReconcilesToExactlyTheManifest() throws Exception {
        GenerationManifest manifest = generator.generate(new GenerationRequest(DATE, 1000, 1234L), inbox);
        long fileId = ingestGenerated(manifest);

        JobExecution execution = reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(fileId));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .startsWith(ReconcileJobKeys.CREATE_RUN_STEP)
                .endsWith(ReconcileJobKeys.FINALISE_STEP, ReconcileJobKeys.REPORT_STEP, ReconcileJobKeys.SUMMARY_STEP)
                .contains(ReconcileJobKeys.MATCH_STEP)
                .hasSize(5 + properties.gridSize());
        assertThat(partitionSteps(execution)).hasSize(properties.gridSize())
                .allSatisfy(partition -> assertThat(partition.getStatus()).isEqualTo(BatchStatus.COMPLETED));

        ExpectedBreaks expected = manifest.expectedBreaks();
        int expectedBreakTotal = expected.amountMismatch() + expected.valueDateMismatch() + expected.missingInBank()
                + expected.missingInLedger() + expected.duplicateInStatement();
        long runId = singleReconRunId(jdbcTemplate, fileId);
        assertThat(breakCountsByType(jdbcTemplate, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "AMOUNT_MISMATCH", expected.amountMismatch(),
                "VALUE_DATE_MISMATCH", expected.valueDateMismatch(),
                "MISSING_IN_BANK", expected.missingInBank(),
                "MISSING_IN_LEDGER", expected.missingInLedger(),
                "DUPLICATE_IN_STATEMENT", expected.duplicateInStatement()));

        Map<String, Object> run = reconRun(jdbcTemplate, runId);
        assertThat(run.get("status")).isEqualTo("COMPLETED");
        assertThat(run.get("matched_count")).isEqualTo(manifest.expectedMatched());
        assertThat(run.get("break_count")).isEqualTo(expectedBreakTotal);
        assertThat(run.get("job_execution_id")).isEqualTo(execution.getId());
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(statementFileStatus(jdbcTemplate, fileId)).isEqualTo("RECONCILED");

        StepExecution manager = step(execution, ReconcileJobKeys.MATCH_STEP);
        assertThat(manager.getReadCount()).isEqualTo(manifest.expectedMatched() + expectedBreakTotal);
        assertThat(manager.getFilterCount()).isEqualTo(manifest.expectedMatched());
        assertThat(manager.getWriteCount()).isEqualTo(expectedBreakTotal);
        assertThat(manager.getCommitCount()).isGreaterThan(properties.gridSize());

        assertThat(jdbcTemplate.queryForList("select details from recon_break where break_type = 'VALUE_DATE_MISMATCH'", String.class))
                .isNotEmpty()
                .allMatch(details -> details.contains("within tolerance of 3 day(s): warning"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_break where status <> 'OPEN'", Integer.class)).isZero();
    }

    @Test
    void oneAndFourPartitionsProduceTheSameBreaks() throws Exception {
        GenerationManifest manifest = generator.generate(new GenerationRequest(DATE, 800, 4321L), inbox);
        long fileId = ingestGenerated(manifest);

        JobExecution single = reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(fileId, 1));
        JobExecution four = reconcile(jobOperatorTestUtils, reconcileJob, rerunParameters(fileId, 4, "compare partitions"));

        assertThat(single.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(four.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(partitionSteps(single)).hasSize(1);
        assertThat(partitionSteps(four)).hasSize(4)
                .allSatisfy(partition -> assertThat(partition.getReadCount()).isPositive());

        List<Long> runIds = jdbcTemplate.queryForList(
                "select id from recon_run where statement_file_id = ? order by id", Long.class, fileId);
        assertThat(runIds).hasSize(2);
        List<String> singleRunBreaks = canonicalBreaks(jdbcTemplate, runIds.get(0));
        List<String> fourRunBreaks = canonicalBreaks(jdbcTemplate, runIds.get(1));

        assertThat(singleRunBreaks).isNotEmpty().isEqualTo(fourRunBreaks);
        assertThat(reconRun(jdbcTemplate, runIds.get(1)))
                .containsEntry("matched_count", manifest.expectedMatched())
                .containsEntry("break_count", singleRunBreaks.size());
        assertThat(reconRun(jdbcTemplate, runIds.get(0)))
                .containsEntry("matched_count", manifest.expectedMatched())
                .containsEntry("break_count", singleRunBreaks.size());
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_break", Integer.class))
                .isEqualTo(2 * singleRunBreaks.size());
    }

    @Test
    void secondRunOnTheSameFileIsRefusedUnlessExplicitlyRequested() throws Exception {
        Path file = copyFixture("statement_clean.csv");
        ingest(jobOperatorTestUtils, ingestJob, file);
        long fileId = statementFileId(jdbcTemplate, "statement_clean.csv");

        JobExecution first = reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(fileId));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long firstRunId = singleReconRunId(jdbcTemplate, fileId);
        assertThat(breakCountsByType(jdbcTemplate, firstRunId)).containsExactly(Map.entry("MISSING_IN_LEDGER", 5));

        assertThatThrownBy(() -> reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(fileId)))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);

        jobRepositoryTestUtils.removeJobExecutions();
        assertThatThrownBy(() -> reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(fileId)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("already RECONCILED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_run", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_break", Integer.class)).isEqualTo(5);

        JobExecution rerun = reconcile(jobOperatorTestUtils, reconcileJob, rerunParameters(fileId, 2, "ledger corrected"));

        assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Long> runIds = jdbcTemplate.queryForList(
                "select id from recon_run where statement_file_id = ? order by id", Long.class, fileId);
        assertThat(runIds).hasSize(2).startsWith(firstRunId);
        assertThat(breakCountsByType(jdbcTemplate, firstRunId)).containsExactly(Map.entry("MISSING_IN_LEDGER", 5));
        assertThat(breakCountsByType(jdbcTemplate, runIds.get(1))).containsExactly(Map.entry("MISSING_IN_LEDGER", 5));
        assertThat(statementFileStatus(jdbcTemplate, fileId)).isEqualTo("RECONCILED");
    }

    // Batch 6 creates the job instance before it calls the validator, so each refused launch
    // below uses its own identifying parameters; what matters is that no execution, no run
    // and no break exists afterwards.
    @Test
    void jobWithoutAReconcilableStatementFileIsRefusedBeforeAnythingStarts() throws Exception {
        Path rejected = copyFixture("statement_wrong_trailer.csv");
        ingest(jobOperatorTestUtils, ingestJob, rejected);
        long rejectedId = statementFileId(jdbcTemplate, "statement_wrong_trailer.csv");

        assertThatThrownBy(() -> reconcile(jobOperatorTestUtils, reconcileJob, new JobParameters()))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("statementFileId");
        assertThatThrownBy(() -> reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(424242L)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(424243L, 0)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("gridSize");
        assertThatThrownBy(() -> reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(rejectedId)))
                .isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("status REJECTED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_run", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from batch_job_execution e
                join batch_job_instance i on i.job_instance_id = e.job_instance_id
                where i.job_name = 'reconcileJob'
                """, Integer.class)).isZero();
    }

    private long ingestGenerated(GenerationManifest manifest) throws Exception {
        JobExecution ingest = ingest(jobOperatorTestUtils, ingestJob, inbox.resolve(manifest.fileName()));
        assertThat(ingest.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long fileId = statementFileId(jdbcTemplate, manifest.fileName());
        assertThat(statementFileStatus(jdbcTemplate, fileId)).isEqualTo("VALIDATED");
        return fileId;
    }

    private Path copyFixture(String name) throws Exception {
        Path fixture = Path.of(getClass().getResource("/fixtures/" + name).toURI());
        return Files.copy(fixture, inbox.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
}
