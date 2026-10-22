package com.darshan.recon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.recon.resolution.ReconBreak;
import com.darshan.recon.resolution.ReconBreakRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

class BreakResolutionApiTest extends ApiTestBase {

    private static final String ACCOUNT = "000123456789";

    @Autowired
    private ReconBreakRepository breaks;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long smallMismatch;
    private long atThreshold;
    private long largeMissingInBank;
    private long largeMissingInLedger;

    @BeforeEach
    void insertARunWithFourOpenBreaks() {
        jdbcTemplate.update("""
                insert into statement_file (file_name, checksum_sha256, bank_code, account_number, statement_date,
                                            declared_count, declared_total, status, received_at)
                values ('resolve.csv', ?, 'BANK01', ?, date '2026-10-01', 3, 0, 'RECONCILED', now())
                """, "f".repeat(64), ACCOUNT);
        long fileId = jdbcTemplate.queryForObject("select id from statement_file", Long.class);
        jdbcTemplate.update("""
                insert into recon_run (statement_file_id, started_at, finished_at, status, matched_count, break_count)
                values (?, now(), now(), 'COMPLETED', 0, 4)
                """, fileId);
        long runId = jdbcTemplate.queryForObject("select id from recon_run", Long.class);
        long ledgerSmall = insertLedger("TXN-1", "100.00");
        long ledgerLarge = insertLedger("TXN-3", "25000.00");
        long lineSmall = insertLine(fileId, 2, "TXN-1", "98.50");
        long lineAtThreshold = insertLine(fileId, 3, "TXN-2", "1000.00");
        long lineLarge = insertLine(fileId, 4, "TXN-4", "5000.00");
        smallMismatch = insertBreak(runId, "AMOUNT_MISMATCH", ledgerSmall, lineSmall, "100.00", "98.50");
        atThreshold = insertBreak(runId, "MISSING_IN_LEDGER", null, lineAtThreshold, null, "1000.00");
        largeMissingInBank = insertBreak(runId, "MISSING_IN_BANK", ledgerLarge, null, "25000.00", null);
        largeMissingInLedger = insertBreak(runId, "MISSING_IN_LEDGER", null, lineLarge, null, "5000.00");
    }

    @Test
    void operatorResolvesABreakAndTheHistoryShowsWhoWhenAndWhy() {
        MvcTestResult resolved = resolve(smallMismatch, "RESOLVED", "bank charges posted separately", OPERATOR);

        assertThat(resolved).hasStatusOk();
        assertThat(resolved).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("RESOLVED"))
                .hasPathSatisfying("$.resolvedBy", r -> r.assertThat().isEqualTo(OPERATOR))
                .hasPathSatisfying("$.resolvedAt", r -> r.assertThat().isNotNull())
                .hasPathSatisfying("$.resolutionComment", c -> c.assertThat().isEqualTo("bank charges posted separately"))
                .hasPathSatisfying("$.exposure", e -> number(e.assertThat(), "1.50"))
                .hasPathSatisfying("$.requestedBy", r -> r.assertThat().isNull())
                .hasPathSatisfying("$.actions", a -> a.assertThat().asArray().hasSize(1))
                .hasPathSatisfying("$.actions[0].action", a -> a.assertThat().isEqualTo("RESOLVE"))
                .hasPathSatisfying("$.actions[0].performedBy", p -> p.assertThat().isEqualTo(OPERATOR))
                .hasPathSatisfying("$.actions[0].comment", c -> c.assertThat().isEqualTo("bank charges posted separately"));

        MvcTestResult viewed = mvc.get().uri("/api/v1/breaks/{id}", smallMismatch).with(as(VIEWER)).exchange();
        assertThat(viewed).hasStatusOk();
        assertThat(viewed).bodyJson().hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("RESOLVED"));

        MvcTestResult again = resolve(smallMismatch, "WRITTEN_OFF", "trying again", SECOND_OPERATOR);
        assertThat(again).hasStatus(409).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(again).bodyJson().extractingPath("$.detail").asString().contains("RESOLVED");
        assertThat(jdbcTemplate.queryForObject("select resolved_by from recon_break where id = ?", String.class, smallMismatch))
                .isEqualTo(OPERATOR);
    }

    @Test
    void writeOffAtOrBelowTheThresholdIsDoneByTheOperatorDirectly() {
        MvcTestResult writtenOff = resolve(atThreshold, "WRITTEN_OFF", "below materiality", OPERATOR);

        assertThat(writtenOff).hasStatusOk();
        assertThat(writtenOff).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("WRITTEN_OFF"))
                .hasPathSatisfying("$.exposure", e -> number(e.assertThat(), "1000.00"))
                .hasPathSatisfying("$.resolvedBy", r -> r.assertThat().isEqualTo(OPERATOR))
                .hasPathSatisfying("$.requestedBy", r -> r.assertThat().isNull())
                .hasPathSatisfying("$.approvedBy", a -> a.assertThat().isNull())
                .hasPathSatisfying("$.actions[*].action", a -> a.assertThat().asArray().containsExactly("WRITE_OFF"));
    }

    @Test
    void writeOffAboveTheThresholdWaitsForADifferentApprover() {
        MvcTestResult requested = resolve(largeMissingInBank, "WRITTEN_OFF", "counterparty confirmed reversal", OPERATOR);
        assertThat(requested).hasStatusOk();
        assertThat(requested).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("PENDING_APPROVAL"))
                .hasPathSatisfying("$.exposure", e -> number(e.assertThat(), "25000.00"))
                .hasPathSatisfying("$.requestedBy", r -> r.assertThat().isEqualTo(OPERATOR))
                .hasPathSatisfying("$.requestedAt", r -> r.assertThat().isNotNull())
                .hasPathSatisfying("$.resolvedBy", r -> r.assertThat().isNull())
                .hasPathSatisfying("$.actions[*].action", a -> a.assertThat().asArray().containsExactly("REQUEST_WRITE_OFF"));

        assertThat(resolve(largeMissingInBank, "RESOLVED", "changed my mind", OPERATOR)).hasStatus(409);
        assertThat(decide("approve", largeMissingInBank, "ok", OPERATOR)).hasStatus(403);

        MvcTestResult approved = decide("approve", largeMissingInBank, "reversal seen on the next statement", APPROVER);
        assertThat(approved).hasStatusOk();
        assertThat(approved).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("WRITTEN_OFF"))
                .hasPathSatisfying("$.requestedBy", r -> r.assertThat().isEqualTo(OPERATOR))
                .hasPathSatisfying("$.resolvedBy", r -> r.assertThat().isEqualTo(OPERATOR))
                .hasPathSatisfying("$.approvedBy", a -> a.assertThat().isEqualTo(APPROVER))
                .hasPathSatisfying("$.approvedAt", a -> a.assertThat().isNotNull())
                .hasPathSatisfying("$.resolutionComment", c -> c.assertThat().isEqualTo("counterparty confirmed reversal"))
                .hasPathSatisfying("$.actions[*].action",
                        a -> a.assertThat().asArray().containsExactly("REQUEST_WRITE_OFF", "APPROVE_WRITE_OFF"))
                .hasPathSatisfying("$.actions[1].performedBy", p -> p.assertThat().isEqualTo(APPROVER))
                .hasPathSatisfying("$.actions[1].comment", c -> c.assertThat().isEqualTo("reversal seen on the next statement"));

        assertThat(decide("approve", largeMissingInBank, "twice", APPROVER)).hasStatus(409);
        assertThat(decide("reject", largeMissingInBank, "too late", APPROVER)).hasStatus(409);
    }

    @Test
    void theRequesterCannotApproveTheirOwnWriteOffEvenWithTheApproverRole() {
        assertThat(resolve(largeMissingInLedger, "WRITTEN_OFF", "please approve", OPERATOR_AND_APPROVER)).hasStatusOk();

        MvcTestResult selfApproval = decide("approve", largeMissingInLedger, "approving my own", OPERATOR_AND_APPROVER);

        assertThat(selfApproval).hasStatus(422).hasContentTypeCompatibleWith(PROBLEM_JSON);
        assertThat(selfApproval).bodyJson()
                .hasPathSatisfying("$.errorCode", c -> c.assertThat().isEqualTo("SELF_APPROVAL"))
                .hasPathSatisfying("$.detail", d -> d.assertThat().asString().contains("cannot be approved by the user who requested it"));
        assertThat(jdbcTemplate.queryForObject("select status from recon_break where id = ?", String.class, largeMissingInLedger))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_break_action where break_id = ?", Integer.class,
                largeMissingInLedger)).isEqualTo(1);

        assertThat(decide("approve", largeMissingInLedger, "independent review done", APPROVER)).hasStatusOk();
    }

    @Test
    void rejectedWriteOffReopensTheBreakAndKeepsTheRequestInTheHistory() {
        assertThat(resolve(largeMissingInLedger, "WRITTEN_OFF", "please approve", OPERATOR)).hasStatusOk();

        MvcTestResult rejected = decide("reject", largeMissingInLedger, "investigate with the branch first", APPROVER);

        assertThat(rejected).hasStatusOk();
        assertThat(rejected).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("OPEN"))
                .hasPathSatisfying("$.requestedBy", r -> r.assertThat().isNull())
                .hasPathSatisfying("$.resolutionComment", c -> c.assertThat().isNull())
                .hasPathSatisfying("$.actions[*].action",
                        a -> a.assertThat().asArray().containsExactly("REQUEST_WRITE_OFF", "REJECT_WRITE_OFF"))
                .hasPathSatisfying("$.actions[1].comment", c -> c.assertThat().isEqualTo("investigate with the branch first"));

        MvcTestResult resolved = resolve(largeMissingInLedger, "RESOLVED", "branch posted it a day late", SECOND_OPERATOR);
        assertThat(resolved).hasStatusOk();
        assertThat(resolved).bodyJson()
                .hasPathSatisfying("$.status", s -> s.assertThat().isEqualTo("RESOLVED"))
                .hasPathSatisfying("$.resolvedBy", r -> r.assertThat().isEqualTo(SECOND_OPERATOR))
                .hasPathSatisfying("$.actions", a -> a.assertThat().asArray().hasSize(3));
    }

    @Test
    void requestsAreValidatedAndUnknownBreaksAre404() {
        MvcTestResult blankComment = resolve(smallMismatch, "RESOLVED", "", OPERATOR);
        assertThat(blankComment).hasStatus(400);
        assertThat(blankComment).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("comment");

        assertThat(resolve(smallMismatch, "BOGUS", "x", OPERATOR)).hasStatus(400);
        assertThat(resolve(smallMismatch, "RESOLVED", "x".repeat(501), OPERATOR)).hasStatus(400);
        assertThat(resolve(999_999, "RESOLVED", "x", OPERATOR)).hasStatus(404);
        assertThat(decide("approve", 999_999, "x", APPROVER)).hasStatus(404);
        assertThat(decide("approve", smallMismatch, "not pending", APPROVER)).hasStatus(409);
        assertThat(mvc.get().uri("/api/v1/breaks/999999").with(as(VIEWER)).exchange()).hasStatus(404);
        assertThat(jdbcTemplate.queryForObject("select count(*) from recon_break where status <> 'OPEN'", Integer.class)).isZero();
    }

    @Test
    void aStaleBreakCannotOverwriteAConcurrentResolution() {
        ReconBreak stale = transactionTemplate.execute(status -> breaks.findById(smallMismatch).orElseThrow());
        assertThat(resolve(smallMismatch, "RESOLVED", "first operator wins", OPERATOR)).hasStatusOk();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> breaks.save(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbcTemplate.queryForObject("select resolved_by from recon_break where id = ?", String.class, smallMismatch))
                .isEqualTo(OPERATOR);
    }

    private MvcTestResult resolve(long breakId, String action, String comment, String user) {
        return mvc.patch().uri("/api/v1/breaks/{id}/resolve", breakId)
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\": \"" + action + "\", \"comment\": \"" + comment + "\"}")
                .exchange();
    }

    private MvcTestResult decide(String decision, long breakId, String comment, String user) {
        return mvc.post().uri("/api/v1/breaks/{id}/" + decision, breakId)
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"" + comment + "\"}")
                .exchange();
    }

    private long insertLedger(String reference, String amount) {
        return jdbcTemplate.queryForObject("""
                insert into ledger_transaction
                    (txn_reference, account_number, amount, currency, booking_date, value_date, description, created_at)
                values (?, ?, ?::numeric, 'INR', date '2026-10-01', date '2026-10-01', 'test', now())
                returning id
                """, Long.class, reference, ACCOUNT, amount);
    }

    private long insertLine(long fileId, int lineNumber, String reference, String amount) {
        return jdbcTemplate.queryForObject("""
                insert into statement_line
                    (statement_file_id, line_number, bank_reference, account_number, amount, currency, value_date)
                values (?, ?, ?, ?, ?::numeric, 'INR', date '2026-10-01')
                returning id
                """, Long.class, fileId, lineNumber, reference, ACCOUNT, amount);
    }

    private long insertBreak(long runId, String type, Long ledgerTxnId, Long lineId, String ledgerAmount, String bankAmount) {
        return jdbcTemplate.queryForObject("""
                insert into recon_break
                    (recon_run_id, break_type, severity, ledger_txn_id, statement_line_id, ledger_amount, bank_amount, details)
                values (?, ?, 'ERROR', ?, ?, ?::numeric, ?::numeric, 'test break')
                returning id
                """, Long.class, runId, type, ledgerTxnId, lineId, ledgerAmount, bankAmount);
    }
}
