package com.darshan.recon.matching;

import static com.darshan.recon.JobMetadataCleanup.removeAllJobs;
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

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.testdata.GenerationManifest;
import com.darshan.recon.testdata.GenerationManifest.ExpectedBreaks;
import com.darshan.recon.testdata.GenerationRequest;
import com.darshan.recon.testdata.TestDataGenerator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
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

// Own context: the fault-injecting processor replaces the production one for this class only.
@SpringBootTest
@SpringBatchTest
@Import({TestcontainersConfiguration.class, MatchingFaultInjection.class})
@ActiveProfiles("test")
class ReconcileJobRestartTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final int FAILING_PARTITION = 0;
    private static final int COMMITTED_CHUNKS_BEFORE_FAILURE = 2;
    private static final String READER_POSITION_KEY = "candidatePairReader.read.count";

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private JobOperator jobOperator;

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

    @Autowired
    private MatchingFault fault;

    @TempDir
    Path inbox;

    @BeforeEach
    void startFromAnEmptyState() {
        fault.disarm();
        removeAllJobs(jobRepositoryTestUtils, jdbcTemplate);
        jdbcTemplate.update("delete from recon_break");
        jdbcTemplate.update("delete from recon_run");
        jdbcTemplate.update("delete from statement_line");
        jdbcTemplate.update("delete from statement_file");
    }

    @Test
    void restartAfterAMidRunFailureResumesTheFailedPartitionAndProducesTheSameBreaks() throws Exception {
        GenerationManifest manifest = generator.generate(new GenerationRequest(DATE, 1200, 777L), inbox);
        ingest(jobOperatorTestUtils, ingestJob, inbox.resolve(manifest.fileName()));
        long fileId = statementFileId(jdbcTemplate, manifest.fileName());
        ExpectedBreaks expected = manifest.expectedBreaks();
        int expectedBreakTotal = expected.amountMismatch() + expected.valueDateMismatch() + expected.missingInBank()
                + expected.missingInLedger() + expected.duplicateInStatement();
        int candidates = manifest.expectedMatched() + expectedBreakTotal;
        int itemsBeforeFailure = COMMITTED_CHUNKS_BEFORE_FAILURE * properties.chunkSize();

        fault.arm(FAILING_PARTITION, itemsBeforeFailure + properties.chunkSize() / 2);
        JobExecution failed = reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(fileId));

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactlyInAnyOrder(ReconcileJobKeys.CREATE_RUN_STEP, ReconcileJobKeys.MATCH_STEP,
                        partitionName(0), partitionName(1));
        StepExecution failedPartition = step(failed, partitionName(FAILING_PARTITION));
        StepExecution completedPartition = step(failed, partitionName(1));
        assertThat(failedPartition.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedPartition.getCommitCount()).isEqualTo(COMMITTED_CHUNKS_BEFORE_FAILURE);
        assertThat(failedPartition.getRollbackCount()).isEqualTo(1);
        // The read count includes the rolled-back chunk; the restart position does not.
        assertThat(failedPartition.getReadCount()).isEqualTo(itemsBeforeFailure + properties.chunkSize());
        assertThat(failedPartition.getExecutionContext().getInt(READER_POSITION_KEY)).isEqualTo(itemsBeforeFailure);
        assertThat(failedPartition.getExitStatus().getExitDescription()).contains("injected failure in partition 0");
        assertThat(completedPartition.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long runId = singleReconRunId(jdbcTemplate, fileId);
        assertThat(reconRun(jdbcTemplate, runId)).containsEntry("status", "FAILED").containsEntry("break_count", 0);
        assertThat(statementFileStatus(jdbcTemplate, fileId)).isEqualTo("VALIDATED");
        long breaksBeforeRestart = countBreaks(runId);
        assertThat(breaksBeforeRestart)
                .isEqualTo(failedPartition.getWriteCount() + completedPartition.getWriteCount())
                .isLessThan(expectedBreakTotal);

        fault.disarm();
        JobExecution restarted = jobOperator.restart(failed);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(restarted.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly(ReconcileJobKeys.MATCH_STEP, partitionName(FAILING_PARTITION),
                        ReconcileJobKeys.FINALISE_STEP, ReconcileJobKeys.REPORT_STEP, ReconcileJobKeys.SUMMARY_STEP);
        StepExecution resumedPartition = step(restarted, partitionName(FAILING_PARTITION));
        assertThat(resumedPartition.getReadCount())
                .isEqualTo(candidates - itemsBeforeFailure - completedPartition.getReadCount());
        assertThat(resumedPartition.getRollbackCount()).isZero();
        assertThat(countBreaks(runId))
                .isEqualTo(breaksBeforeRestart + resumedPartition.getWriteCount())
                .isEqualTo(expectedBreakTotal);
        assertThat(duplicateBreaks(runId)).isZero();
        assertThat(breakCountsByType(jdbcTemplate, runId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "AMOUNT_MISMATCH", expected.amountMismatch(),
                "VALUE_DATE_MISMATCH", expected.valueDateMismatch(),
                "MISSING_IN_BANK", expected.missingInBank(),
                "MISSING_IN_LEDGER", expected.missingInLedger(),
                "DUPLICATE_IN_STATEMENT", expected.duplicateInStatement()));
        assertThat(reconRun(jdbcTemplate, runId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("matched_count", manifest.expectedMatched())
                .containsEntry("break_count", expectedBreakTotal);
        assertThat(statementFileStatus(jdbcTemplate, fileId)).isEqualTo("RECONCILED");

        JobExecution uninterrupted = reconcile(jobOperatorTestUtils, reconcileJob,
                rerunParameters(fileId, properties.gridSize(), "uninterrupted reference run"));

        assertThat(uninterrupted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Long> runIds = jdbcTemplate.queryForList(
                "select id from recon_run where statement_file_id = ? order by id", Long.class, fileId);
        assertThat(runIds).hasSize(2).startsWith(runId);
        assertThat(canonicalBreaks(jdbcTemplate, runId))
                .hasSize(expectedBreakTotal)
                .isEqualTo(canonicalBreaks(jdbcTemplate, runIds.get(1)));
        assertThat(partitionSteps(uninterrupted)).hasSize(properties.gridSize());
    }

    private static String partitionName(int index) {
        return ReconcileJobKeys.PARTITION_STEP + ":" + ReferenceHashPartitioner.PARTITION_PREFIX + index;
    }

    private long countBreaks(long runId) {
        return jdbcTemplate.queryForObject("select count(*) from recon_break where recon_run_id = ?", Long.class, runId);
    }

    // The same pair reported twice would show up as two rows with the same sides and type.
    private int duplicateBreaks(long runId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from (
                    select break_type, ledger_txn_id, statement_line_id
                    from recon_break where recon_run_id = ?
                    group by break_type, ledger_txn_id, statement_line_id
                    having count(*) > 1
                ) duplicates
                """, Integer.class, runId);
    }
}
