package com.darshan.recon.matching;

import static com.darshan.recon.matching.ReconcileJobTestSupport.canonicalBreaks;
import static com.darshan.recon.matching.ReconcileJobTestSupport.partitionSteps;
import static com.darshan.recon.matching.ReconcileJobTestSupport.reconRun;
import static com.darshan.recon.matching.ReconcileJobTestSupport.reconcile;
import static com.darshan.recon.matching.ReconcileJobTestSupport.reconcileParameters;
import static com.darshan.recon.matching.ReconcileJobTestSupport.singleReconRunId;
import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

// Hand-made rows prove that the SQL pairing agrees with the Java normalisation and that the
// ledger window, the tolerances and the duplicate rule behave as documented.
@SpringBootTest
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReconcileScenarioTest {

    private static final String ACCOUNT = "000999888777";
    private static final String OTHER_ACCOUNT = "000111222333";
    private static final LocalDate STATEMENT_DATE = LocalDate.of(2026, 10, 1);
    private static final String CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("reconcileJob")
    private Job reconcileJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private long fileId;

    @BeforeEach
    void loadScenario() {
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("delete from recon_break");
        jdbcTemplate.update("delete from recon_run");
        jdbcTemplate.update("delete from statement_line");
        jdbcTemplate.update("delete from statement_file");
        jdbcTemplate.update("delete from ledger_transaction where account_number in (?, ?)", ACCOUNT, OTHER_ACCOUNT);

        ledger("TXN-A", "100.00", STATEMENT_DATE, ACCOUNT);
        ledger("TXN  B", "200.00", STATEMENT_DATE, ACCOUNT);
        ledger("TXN-C", "-450.00", STATEMENT_DATE, ACCOUNT);
        ledger("TXN-D", "450.00", STATEMENT_DATE, ACCOUNT);
        ledger("TXN-E", "10.00", STATEMENT_DATE, ACCOUNT);
        ledger("TXN-F", "300.00", STATEMENT_DATE, ACCOUNT);
        ledger("TXN-G", "400.00", STATEMENT_DATE, ACCOUNT);
        ledger("TXN-H", "500.00", LocalDate.of(2026, 9, 20), ACCOUNT);
        ledger("TXN-I", "600.00", LocalDate.of(2026, 10, 4), ACCOUNT);
        ledger("TXN-J", "700.00", STATEMENT_DATE, OTHER_ACCOUNT);
        ledger("TXN-L", "800.00", STATEMENT_DATE, ACCOUNT);

        fileId = jdbcTemplate.queryForObject("""
                insert into statement_file
                    (file_name, checksum_sha256, bank_code, account_number, statement_date, declared_count,
                     declared_total, status, received_at)
                values (?, ?, 'BANK01', ?, ?, 13, 0.00, 'VALIDATED', ?)
                returning id
                """, Long.class, "scenario.csv", CHECKSUM, ACCOUNT, STATEMENT_DATE, OffsetDateTime.now(clock));
        line(2, " txn-a ", "100.00", STATEMENT_DATE);
        line(3, "txn b", "200.00", STATEMENT_DATE);
        line(4, "TXN-C", "-450.00", STATEMENT_DATE);
        line(5, "TXN-D", "-450.00", STATEMENT_DATE);
        line(6, "TXN-E", "10.05", STATEMENT_DATE);
        line(7, "TXN-F", "300.00", LocalDate.of(2026, 10, 3));
        line(8, "TXN-G", "400.00", LocalDate.of(2026, 10, 20));
        line(9, "TXN-K", "50.00", STATEMENT_DATE);
        line(10, "TXN-L", "800.00", STATEMENT_DATE);
        line(11, "TXN-L", "800.00", STATEMENT_DATE);
        line(12, "TXN-L", "800.00", STATEMENT_DATE);
        line(13, "TXN-M", "60.00", STATEMENT_DATE);
        line(14, "TXN-M", "60.00", STATEMENT_DATE);
    }

    @Test
    void pairsOnNormalisedReferencesInsideTheLedgerWindowAndClassifiesEveryDifference() throws Exception {
        JobExecution execution = reconcile(jobOperatorTestUtils, reconcileJob, reconcileParameters(fileId, 3));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(partitionSteps(execution)).hasSize(3);

        long runId = singleReconRunId(jdbcTemplate, fileId);
        assertThat(canonicalBreaks(jdbcTemplate, runId)).containsExactly(
                "AMOUNT_MISMATCH|TXN-D|5|450.00|-450.00|amount differs by -900.00 (ledger 450.00, bank -450.00, tolerance 0.00)|OPEN",
                "AMOUNT_MISMATCH|TXN-E|6|10.00|10.05|amount differs by 0.05 (ledger 10.00, bank 10.05, tolerance 0.00)|OPEN",
                "DUPLICATE_IN_STATEMENT|TXN-L|10|800.00|800.00|reference TXN-L appears 3 times in the statement (lines 10, 11, 12); ledger transaction TXN-L left unmatched|OPEN",
                "DUPLICATE_IN_STATEMENT|null|13|null|60.00|reference TXN-M appears 2 times in the statement (lines 13, 14); no ledger transaction|OPEN",
                "MISSING_IN_BANK|TXN-I|null|600.00|null|ledger transaction TXN-I with value date 2026-10-04 has no statement line|OPEN",
                "MISSING_IN_LEDGER|null|9|null|50.00|statement line 9 (TXN-K) has no ledger transaction|OPEN",
                "VALUE_DATE_MISMATCH|TXN-F|7|300.00|300.00|value date differs by 2 day(s) (ledger 2026-10-01, bank 2026-10-03), within tolerance of 3 day(s): warning|OPEN",
                "VALUE_DATE_MISMATCH|TXN-G|8|400.00|400.00|value date differs by 19 day(s) (ledger 2026-10-01, bank 2026-10-20), outside tolerance of 3 day(s): error|OPEN");

        assertThat(reconRun(jdbcTemplate, runId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("matched_count", 3)
                .containsEntry("break_count", 8);
    }

    private void ledger(String reference, String amount, LocalDate valueDate, String account) {
        jdbcTemplate.update("""
                insert into ledger_transaction
                    (txn_reference, account_number, amount, currency, booking_date, value_date, description, created_at)
                values (?, ?, ?, 'INR', ?, ?, null, ?)
                """, reference, account, new BigDecimal(amount), STATEMENT_DATE, valueDate, OffsetDateTime.now(clock));
    }

    private void line(int lineNumber, String reference, String amount, LocalDate valueDate) {
        jdbcTemplate.update("""
                insert into statement_line
                    (statement_file_id, line_number, bank_reference, account_number, amount, currency, value_date, description)
                values (?, ?, ?, ?, ?, 'INR', ?, null)
                """, fileId, lineNumber, reference, ACCOUNT, new BigDecimal(amount), valueDate);
    }
}
