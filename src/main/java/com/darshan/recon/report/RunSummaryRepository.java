package com.darshan.recon.report;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.ReconRunStatus;
import com.darshan.recon.resolution.BreakStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RunSummaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public RunSummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RunSummary> findByRunId(long reconRunId) {
        Map<ReconBreakType, Integer> byType = zeroed(ReconBreakType.class);
        Map<BreakStatus, Integer> byStatus = zeroed(BreakStatus.class);
        jdbcTemplate.query("""
                select break_type, status, count(*) as n
                from recon_break where recon_run_id = ?
                group by break_type, status
                """, rs -> {
            int count = rs.getInt("n");
            byType.merge(ReconBreakType.valueOf(rs.getString("break_type")), count, Integer::sum);
            byStatus.merge(BreakStatus.valueOf(rs.getString("status")), count, Integer::sum);
        }, reconRunId);

        return jdbcTemplate.query("""
                        select r.id, r.statement_file_id, f.file_name, f.account_number, f.statement_date,
                               r.status, r.started_at, r.finished_at, r.matched_count, r.break_count
                        from recon_run r
                        join statement_file f on f.id = r.statement_file_id
                        where r.id = ?
                        """,
                        (rs, rowNum) -> new RunSummary(
                                rs.getLong("id"),
                                rs.getLong("statement_file_id"),
                                rs.getString("file_name"),
                                rs.getString("account_number"),
                                rs.getObject("statement_date", LocalDate.class),
                                ReconRunStatus.valueOf(rs.getString("status")),
                                rs.getObject("started_at", OffsetDateTime.class),
                                rs.getObject("finished_at", OffsetDateTime.class),
                                rs.getInt("matched_count"),
                                rs.getInt("break_count"),
                                RunSummary.matchRate(rs.getInt("matched_count"), rs.getInt("break_count")),
                                byType,
                                byStatus),
                        reconRunId)
                .stream()
                .findFirst();
    }

    private static <E extends Enum<E>> Map<E, Integer> zeroed(Class<E> type) {
        Map<E, Integer> counts = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            counts.put(constant, 0);
        }
        return counts;
    }
}
