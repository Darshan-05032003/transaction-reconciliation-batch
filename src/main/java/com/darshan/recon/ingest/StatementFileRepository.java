package com.darshan.recon.ingest;

import com.darshan.recon.ingest.file.StatementHeader;
import com.darshan.recon.ingest.file.StatementTrailer;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatementFileRepository {

    private static final int MAX_REASON_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;

    public StatementFileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> findIdByChecksum(String checksumSha256) {
        return jdbcTemplate.query(
                        "select id from statement_file where checksum_sha256 = ?",
                        (rs, rowNum) -> rs.getLong("id"), checksumSha256)
                .stream()
                .findFirst();
    }

    public long insertReceived(String fileName, String checksumSha256, OffsetDateTime receivedAt) {
        Long id = jdbcTemplate.queryForObject("""
                insert into statement_file (file_name, checksum_sha256, status, received_at)
                values (?, ?, ?, ?)
                returning id
                """, Long.class, fileName, checksumSha256, StatementFileStatus.RECEIVED.name(), receivedAt);
        if (id == null) {
            throw new IllegalStateException("insert into statement_file returned no id");
        }
        return id;
    }

    public void recordHeaderAndTrailer(long id, StatementHeader header, StatementTrailer trailer) {
        int updated = jdbcTemplate.update("""
                update statement_file
                set bank_code = ?, account_number = ?, statement_date = ?, declared_count = ?, declared_total = ?
                where id = ?
                """, header.bankCode(), header.accountNumber(), header.statementDate(),
                trailer.recordCount(), trailer.totalAmount(), id);
        requireOneRow(id, updated);
    }

    public void markValidated(long id) {
        updateStatus(id, StatementFileStatus.VALIDATED, null);
    }

    public void markRejected(long id, String reason) {
        updateStatus(id, StatementFileStatus.REJECTED, reason);
    }

    public void markFailed(long id, String reason) {
        updateStatus(id, StatementFileStatus.FAILED, reason);
    }

    private void updateStatus(long id, StatementFileStatus status, String reason) {
        int updated = jdbcTemplate.update(
                "update statement_file set status = ?, rejection_reason = ? where id = ?",
                status.name(), truncate(reason), id);
        requireOneRow(id, updated);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_REASON_LENGTH);
    }

    private static void requireOneRow(long id, int updated) {
        if (updated != 1) {
            throw new IllegalStateException("statement_file " + id + " does not exist");
        }
    }
}
