package com.darshan.recon.matching;

import com.darshan.recon.ingest.IngestJobKeys;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

// Lives outside the test classes on purpose: @SpringBatchTest treats any test method that
// returns a JobExecution or a StepExecution as a factory for a fake scoped execution.
final class ReconcileJobTestSupport {

    private ReconcileJobTestSupport() {
    }

    static JobExecution ingest(JobOperatorTestUtils utils, Job ingestJob, Path file) throws Exception {
        utils.setJob(ingestJob);
        return utils.startJob(new JobParametersBuilder()
                .addString(IngestJobKeys.FILE_PATH_PARAMETER, file.toString())
                .toJobParameters());
    }

    static JobExecution reconcile(JobOperatorTestUtils utils, Job reconcileJob, JobParameters parameters)
            throws Exception {
        utils.setJob(reconcileJob);
        return utils.startJob(parameters);
    }

    static JobParameters reconcileParameters(long statementFileId) {
        return new JobParametersBuilder()
                .addLong(ReconcileJobKeys.STATEMENT_FILE_ID_PARAMETER, statementFileId)
                .toJobParameters();
    }

    static JobParameters reconcileParameters(long statementFileId, int gridSize) {
        return new JobParametersBuilder(reconcileParameters(statementFileId))
                .addLong(ReconcileJobKeys.GRID_SIZE_PARAMETER, (long) gridSize, false)
                .toJobParameters();
    }

    static JobParameters rerunParameters(long statementFileId, int gridSize, String rerun) {
        return new JobParametersBuilder(reconcileParameters(statementFileId, gridSize))
                .addString(ReconcileJobKeys.RERUN_PARAMETER, rerun)
                .toJobParameters();
    }

    static StepExecution step(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> stepName.equals(step.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("step " + stepName + " did not run"));
    }

    static List<StepExecution> partitionSteps(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().startsWith(ReconcileJobKeys.PARTITION_STEP + ":"))
                .toList();
    }

    static Map<String, Integer> breakCountsByType(JdbcTemplate jdbcTemplate, long reconRunId) {
        return jdbcTemplate.query(
                "select break_type, count(*) as n from recon_break where recon_run_id = ? group by break_type",
                rs -> {
                    Map<String, Integer> counts = new java.util.HashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("break_type"), rs.getInt("n"));
                    }
                    return counts;
                }, reconRunId);
    }

    // The business content of a run's breaks, independent of generated ids and of the run.
    static List<String> canonicalBreaks(JdbcTemplate jdbcTemplate, long reconRunId) {
        return jdbcTemplate.query("""
                select b.break_type, l.txn_reference, s.line_number, b.ledger_amount, b.bank_amount, b.details, b.status
                from recon_break b
                left join ledger_transaction l on l.id = b.ledger_txn_id
                left join statement_line s on s.id = b.statement_line_id
                where b.recon_run_id = ?
                order by b.break_type, l.txn_reference, s.line_number, b.details
                """,
                (rs, rowNum) -> String.join("|",
                        rs.getString("break_type"),
                        String.valueOf(rs.getString("txn_reference")),
                        String.valueOf(rs.getObject("line_number")),
                        String.valueOf(rs.getBigDecimal("ledger_amount")),
                        String.valueOf(rs.getBigDecimal("bank_amount")),
                        rs.getString("details"),
                        rs.getString("status")),
                reconRunId);
    }

    static Map<String, Object> reconRun(JdbcTemplate jdbcTemplate, long reconRunId) {
        return jdbcTemplate.queryForMap("select * from recon_run where id = ?", reconRunId);
    }

    static long singleReconRunId(JdbcTemplate jdbcTemplate, long statementFileId) {
        return jdbcTemplate.queryForObject(
                "select id from recon_run where statement_file_id = ?", Long.class, statementFileId);
    }

    static long statementFileId(JdbcTemplate jdbcTemplate, String fileName) {
        return jdbcTemplate.queryForObject("select id from statement_file where file_name = ?", Long.class, fileName);
    }

    static String statementFileStatus(JdbcTemplate jdbcTemplate, long statementFileId) {
        return jdbcTemplate.queryForObject("select status from statement_file where id = ?", String.class, statementFileId);
    }
}
