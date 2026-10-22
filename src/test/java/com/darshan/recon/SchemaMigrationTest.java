package com.darshan.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesEveryMigrationSuccessfully() {
        List<String> applied = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank",
                String.class);

        assertThat(applied).containsExactly("1", "2", "3");
    }

    @Test
    void createsBatchMetadataAndApplicationTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "batch_job_instance", "batch_job_execution", "batch_job_execution_params",
                "batch_step_execution", "batch_step_execution_context", "batch_job_execution_context",
                "ledger_transaction", "statement_file", "statement_line", "recon_run", "recon_break",
                "recon_break_action", "app_user", "app_user_role");
    }

    @Test
    void createsBatchSequences() {
        List<String> sequences = jdbcTemplate.queryForList(
                "select sequence_name from information_schema.sequences where sequence_schema = 'public'",
                String.class);

        assertThat(sequences).contains(
                "batch_job_instance_seq", "batch_job_execution_seq", "batch_step_execution_seq");
    }

    @Test
    void createsIndexesForTheMatchingPaths() {
        List<String> indexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public'", String.class);

        assertThat(indexes).contains(
                "uk_ledger_transaction_reference",
                "ix_ledger_transaction_account_value_date",
                "uk_statement_file_checksum",
                "ix_statement_file_account_statement_date",
                "uk_statement_line_file_line",
                "ix_statement_line_file_reference",
                "ix_recon_run_statement_file",
                "ix_recon_run_job_execution",
                "ix_recon_break_run_type_status",
                "ix_recon_break_ledger_txn",
                "ix_recon_break_statement_line",
                "ix_recon_break_action_break",
                "uk_app_user_username");
    }

    @Test
    @Transactional
    void rejectsDuplicateStatementFileChecksum() {
        String insert = """
                insert into statement_file (file_name, checksum_sha256, status, received_at)
                values (?, ?, 'RECEIVED', now())
                """;
        String checksum = "a".repeat(64);
        jdbcTemplate.update(insert, "stmt_2026-10-01.csv", checksum);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, "stmt_2026-10-01_copy.csv", checksum))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_statement_file_checksum");
    }

    @Test
    @Transactional
    void rejectsUnknownStatementFileStatus() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into statement_file (file_name, checksum_sha256, status, received_at)
                values ('stmt.csv', ?, 'PENDING', now())
                """, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_statement_file_status");
    }

    @Test
    @Transactional
    void rejectsPendingApprovalWithoutARequester() {
        jdbcTemplate.update("""
                insert into statement_file (file_name, checksum_sha256, status, received_at)
                values ('stmt.csv', ?, 'VALIDATED', now())
                """, "d".repeat(64));
        Long fileId = jdbcTemplate.queryForObject(
                "select id from statement_file where checksum_sha256 = ?", Long.class, "d".repeat(64));
        jdbcTemplate.update("""
                insert into recon_run (statement_file_id, started_at, status) values (?, now(), 'RUNNING')
                """, fileId);
        Long runId = jdbcTemplate.queryForObject(
                "select id from recon_run where statement_file_id = ?", Long.class, fileId);
        jdbcTemplate.update("""
                insert into statement_line (statement_file_id, line_number, bank_reference, account_number, amount, currency, value_date)
                values (?, 2, 'TXN-1', '000123456789', 10.00, 'INR', date '2026-10-01')
                """, fileId);
        Long lineId = jdbcTemplate.queryForObject(
                "select id from statement_line where statement_file_id = ?", Long.class, fileId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into recon_break (recon_run_id, break_type, statement_line_id, status)
                values (?, 'MISSING_IN_LEDGER', ?, 'PENDING_APPROVAL')
                """, runId, lineId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_recon_break_resolution");
    }

    @Test
    @Transactional
    void rejectsBreakWithoutEitherSide() {
        jdbcTemplate.update("""
                insert into statement_file (file_name, checksum_sha256, status, received_at)
                values ('stmt.csv', ?, 'VALIDATED', now())
                """, "c".repeat(64));
        Long fileId = jdbcTemplate.queryForObject(
                "select id from statement_file where checksum_sha256 = ?", Long.class, "c".repeat(64));
        jdbcTemplate.update("""
                insert into recon_run (statement_file_id, started_at, status) values (?, now(), 'RUNNING')
                """, fileId);
        Long runId = jdbcTemplate.queryForObject(
                "select id from recon_run where statement_file_id = ?", Long.class, fileId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into recon_break (recon_run_id, break_type) values (?, 'MISSING_IN_BANK')
                """, runId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_recon_break_has_side");
    }
}
