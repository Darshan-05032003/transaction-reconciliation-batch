package com.darshan.recon.report;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.engine.BreakSeverity;
import com.darshan.recon.resolution.BreakStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import org.springframework.jdbc.core.RowMapper;

final class BreakReportQuery {

    // Ordered by type then id so that the report groups breaks the way operators work
    // through them and the cursor reader can resume deterministically after a restart.
    static final String SQL = """
            select b.id, b.break_type, b.severity, b.ledger_amount, b.bank_amount, b.details, b.status,
                   l.txn_reference, l.value_date as ledger_value_date,
                   s.bank_reference, s.line_number, s.value_date as bank_value_date
            from recon_break b
            left join ledger_transaction l on l.id = b.ledger_txn_id
            left join statement_line s on s.id = b.statement_line_id
            where b.recon_run_id = ?
            order by b.break_type, b.id
            """;

    static final RowMapper<BreakReportRow> ROW_MAPPER = BreakReportQuery::mapRow;

    private BreakReportQuery() {
    }

    private static BreakReportRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new BreakReportRow(
                rs.getLong("id"),
                ReconBreakType.valueOf(rs.getString("break_type")),
                BreakSeverity.valueOf(rs.getString("severity")),
                rs.getString("txn_reference"),
                rs.getString("bank_reference"),
                rs.getObject("line_number", Integer.class),
                rs.getBigDecimal("ledger_amount"),
                rs.getBigDecimal("bank_amount"),
                rs.getObject("ledger_value_date", LocalDate.class),
                rs.getObject("bank_value_date", LocalDate.class),
                rs.getString("details"),
                BreakStatus.valueOf(rs.getString("status")));
    }
}
