package com.darshan.recon.common.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

// Refreshes the planner statistics of a table right after a bulk load. Autovacuum does the
// same eventually, but a job that queries the new rows seconds later would otherwise plan
// on statistics that do not know they exist.
public class AnalyzeTableTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeTableTasklet.class);

    private final JdbcTemplate jdbcTemplate;
    private final String table;

    public AnalyzeTableTasklet(JdbcTemplate jdbcTemplate, String table) {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("table must be a plain identifier: " + table);
        }
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        long started = System.nanoTime();
        jdbcTemplate.execute("analyze " + table);
        log.info("Analyzed {} in {} ms", table, (System.nanoTime() - started) / 1_000_000);
        return RepeatStatus.FINISHED;
    }
}
