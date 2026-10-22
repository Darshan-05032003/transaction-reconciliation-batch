package com.darshan.recon.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.TestcontainersConfiguration;
import com.darshan.recon.common.config.ReconProperties;
import com.darshan.recon.ingest.IngestJobKeys;
import com.darshan.recon.matching.ReconcileJobKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
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
class BreakReportStepTest {

    private static final String ACCOUNT = "000123456789";
    private static final String REFERENCE_PREFIX = "TXN20261001-" + ACCOUNT + "-";

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

    @TempDir
    Path inbox;

    // The ledger rows for the fixture's account are removed again afterwards because the
    // other job tests expect the clean fixture to reconcile against an empty ledger.
    @BeforeEach
    @AfterEach
    void startFromAnEmptyState() {
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("delete from recon_break_action");
        jdbcTemplate.update("delete from recon_break");
        jdbcTemplate.update("delete from recon_run");
        jdbcTemplate.update("delete from statement_line");
        jdbcTemplate.update("delete from statement_file");
        jdbcTemplate.update("delete from ledger_transaction where account_number = ?", ACCOUNT);
    }

    @Test
    void writesEveryBreakToTheReportAndTheTotalsToTheSummary() throws Exception {
        insertLedger("0000001", "1250.50", "2026-10-01");
        insertLedger("0000002", "100.00", "2026-10-01");
        insertLedger("0000004", "20000.00", "2026-10-03");
        Path file = copyFixture("statement_clean.csv");
        jobOperatorTestUtils.setJob(ingestJob);
        jobOperatorTestUtils.startJob(new JobParametersBuilder()
                .addString(IngestJobKeys.FILE_PATH_PARAMETER, file.toString())
                .toJobParameters());
        long fileId = jdbcTemplate.queryForObject("select id from statement_file", Long.class);

        jobOperatorTestUtils.setJob(reconcileJob);
        JobExecution execution = jobOperatorTestUtils.startJob(new JobParametersBuilder()
                .addLong(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER, fileId)
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long runId = jdbcTemplate.queryForObject("select id from recon_run", Long.class);
        StepExecution reportStep = execution.getStepExecutions().stream()
                .filter(step -> ReconcileJobKeys.REPORT_STEP.equals(step.getStepName()))
                .findFirst().orElseThrow();
        assertThat(reportStep.getReadCount()).isEqualTo(4);
        assertThat(reportStep.getWriteCount()).isEqualTo(4);

        Path report = properties.folders().reports().resolve("recon_2026-10-01_" + runId + ".csv");
        List<String> rows = Files.readAllLines(report);
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).isEqualTo(BreakReportRow.HEADER);
        List<String> withoutIds = rows.subList(1, rows.size()).stream()
                .map(row -> row.substring(row.indexOf(',') + 1))
                .toList();
        // Rows are grouped by break type; details are quoted only when they contain a comma.
        assertThat(withoutIds).containsExactly(
                "AMOUNT_MISMATCH,ERROR," + REFERENCE_PREFIX + "0000002," + REFERENCE_PREFIX + "0000002,3,100.00,99.99,"
                        + "2026-10-01,2026-10-01,\"amount differs by -0.01 (ledger 100.00, bank 99.99, tolerance 0.00)\",OPEN",
                "MISSING_IN_LEDGER,ERROR,," + REFERENCE_PREFIX + "0000003,4,,-450.00,,2026-10-01,"
                        + "statement line 4 (" + REFERENCE_PREFIX + "0000003) has no ledger transaction,OPEN",
                "MISSING_IN_LEDGER,ERROR,," + REFERENCE_PREFIX + "0000005,6,,15.25,,2026-10-01,"
                        + "statement line 6 (" + REFERENCE_PREFIX + "0000005) has no ledger transaction,OPEN",
                "VALUE_DATE_MISMATCH,WARNING," + REFERENCE_PREFIX + "0000004," + REFERENCE_PREFIX + "0000004,5,20000.00,20000.00,"
                        + "2026-10-03,2026-10-01,\"value date differs by -2 day(s) (ledger 2026-10-03, bank 2026-10-01), "
                        + "within tolerance of 3 day(s): warning\",OPEN");

        Path summary = properties.folders().reports().resolve("recon_2026-10-01_" + runId + "_summary.csv");
        assertThat(Files.readAllLines(summary)).containsExactly(
                "metric,value",
                "recon_run_id," + runId,
                "statement_file_id," + fileId,
                "file_name,statement_clean.csv",
                "account_number," + ACCOUNT,
                "statement_date,2026-10-01",
                "status,COMPLETED",
                "matched_count,1",
                "break_count,4",
                "match_rate_percent,20.00",
                "breaks_amount_mismatch,1",
                "breaks_value_date_mismatch,1",
                "breaks_missing_in_bank,0",
                "breaks_missing_in_ledger,2",
                "breaks_duplicate_in_statement,0");
    }

    private void insertLedger(String suffix, String amount, String valueDate) {
        jdbcTemplate.update("""
                insert into ledger_transaction
                    (txn_reference, account_number, amount, currency, booking_date, value_date, description, created_at)
                values (?, ?, ?::numeric, 'INR', ?::date, ?::date, 'test', now())
                """, REFERENCE_PREFIX + suffix, ACCOUNT, amount, valueDate, valueDate);
    }

    private Path copyFixture(String name) throws Exception {
        Path fixture = Path.of(getClass().getResource("/fixtures/" + name).toURI());
        return Files.copy(fixture, inbox.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
}
