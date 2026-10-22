package com.darshan.recon.api;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.engine.BreakSeverity;
import com.darshan.recon.resolution.BreakStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

// Read side of the breaks listing: plain SQL with optional filters, served by the
// (recon_run_id, break_type, status) index from the first migration.
@Repository
public class BreakQueryRepository {

    private static final String SELECT = """
            select b.id, b.break_type, b.severity, b.ledger_txn_id, l.txn_reference, b.statement_line_id,
                   s.bank_reference, s.line_number, b.ledger_amount, b.bank_amount,
                   l.value_date as ledger_value_date, s.value_date as bank_value_date,
                   b.details, b.status, b.resolved_by, b.resolved_at, b.requested_by, b.approved_by
            from recon_break b
            left join ledger_transaction l on l.id = b.ledger_txn_id
            left join statement_line s on s.id = b.statement_line_id
            """;

    private static final RowMapper<BreakListItem> ROW_MAPPER = (rs, rowNum) -> new BreakListItem(
            rs.getLong("id"),
            ReconBreakType.valueOf(rs.getString("break_type")),
            BreakSeverity.valueOf(rs.getString("severity")),
            rs.getObject("ledger_txn_id", Long.class),
            rs.getString("txn_reference"),
            rs.getObject("statement_line_id", Long.class),
            rs.getString("bank_reference"),
            rs.getObject("line_number", Integer.class),
            rs.getBigDecimal("ledger_amount"),
            rs.getBigDecimal("bank_amount"),
            rs.getObject("ledger_value_date", LocalDate.class),
            rs.getObject("bank_value_date", LocalDate.class),
            rs.getString("details"),
            BreakStatus.valueOf(rs.getString("status")),
            rs.getString("resolved_by"),
            rs.getObject("resolved_at", OffsetDateTime.class),
            rs.getString("requested_by"),
            rs.getString("approved_by"));

    private final JdbcTemplate jdbcTemplate;

    public BreakQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<BreakListItem> findByRun(long reconRunId, ReconBreakType type, BreakStatus status,
                                                 int page, int size) {
        StringBuilder where = new StringBuilder(" where b.recon_run_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(reconRunId);
        if (type != null) {
            where.append(" and b.break_type = ?");
            arguments.add(type.name());
        }
        if (status != null) {
            where.append(" and b.status = ?");
            arguments.add(status.name());
        }
        Long total = jdbcTemplate.queryForObject("select count(*) from recon_break b" + where, Long.class,
                arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        List<BreakListItem> content = jdbcTemplate.query(SELECT + where + " order by b.id limit ? offset ?",
                ROW_MAPPER, pageArguments.toArray());
        return PageResponse.of(content, page, size, total == null ? 0 : total);
    }
}
