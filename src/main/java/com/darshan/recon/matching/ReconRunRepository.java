package com.darshan.recon.matching;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReconRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertRunning(long statementFileId, Long jobExecutionId, OffsetDateTime startedAt) {
        Long id = jdbcTemplate.queryForObject("""
                insert into recon_run (statement_file_id, job_execution_id, started_at, status)
                values (?, ?, ?, ?)
                returning id
                """, Long.class, statementFileId, jobExecutionId, startedAt, ReconRunStatus.RUNNING.name());
        if (id == null) {
            throw new IllegalStateException("insert into recon_run returned no id");
        }
        return id;
    }

    public void markRunning(long id) {
        int updated = jdbcTemplate.update(
                "update recon_run set status = ?, finished_at = null where id = ?",
                ReconRunStatus.RUNNING.name(), id);
        requireOneRow(id, updated);
    }

    public void complete(long id, int matchedCount, int breakCount, OffsetDateTime finishedAt) {
        int updated = jdbcTemplate.update("""
                update recon_run
                set status = ?, matched_count = ?, break_count = ?, finished_at = ?
                where id = ?
                """, ReconRunStatus.COMPLETED.name(), matchedCount, breakCount, finishedAt, id);
        requireOneRow(id, updated);
    }

    public void markFailed(long id, OffsetDateTime finishedAt) {
        int updated = jdbcTemplate.update(
                "update recon_run set status = ?, finished_at = ? where id = ?",
                ReconRunStatus.FAILED.name(), finishedAt, id);
        requireOneRow(id, updated);
    }

    public boolean exists(long id) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from recon_run where id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    public boolean hasRunningRun(long statementFileId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from recon_run where statement_file_id = ? and status = ?",
                Integer.class, statementFileId, ReconRunStatus.RUNNING.name());
        return count != null && count > 0;
    }

    public int countBreaks(long reconRunId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from recon_break where recon_run_id = ?", Integer.class, reconRunId);
        return count == null ? 0 : count;
    }

    public int countCandidates(long statementFileId, String accountNumber, ReconWindow window) {
        Integer count = jdbcTemplate.queryForObject(CandidatePairQuery.COUNT_SQL, Integer.class,
                CandidatePairQuery.countParameters(statementFileId, accountNumber, window));
        return count == null ? 0 : count;
    }

    private static void requireOneRow(long id, int updated) {
        if (updated != 1) {
            throw new IllegalStateException("recon_run " + id + " does not exist");
        }
    }
}
