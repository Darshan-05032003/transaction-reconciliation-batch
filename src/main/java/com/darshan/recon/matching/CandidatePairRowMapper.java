package com.darshan.recon.matching;

import com.darshan.recon.matching.engine.CandidatePair;
import com.darshan.recon.matching.engine.LedgerSide;
import com.darshan.recon.matching.engine.StatementSide;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;

final class CandidatePairRowMapper implements RowMapper<CandidatePair> {

    @Override
    public CandidatePair mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CandidatePair(ledgerSide(rs), statementSide(rs), copyLineNumbers(rs));
    }

    private static LedgerSide ledgerSide(ResultSet rs) throws SQLException {
        long id = rs.getLong("ledger_id");
        if (rs.wasNull()) {
            return null;
        }
        return new LedgerSide(id, rs.getString("txn_reference"), rs.getBigDecimal("ledger_amount"),
                rs.getObject("ledger_value_date", LocalDate.class), rs.getString("ledger_description"));
    }

    private static StatementSide statementSide(ResultSet rs) throws SQLException {
        long id = rs.getLong("statement_line_id");
        if (rs.wasNull()) {
            return null;
        }
        return new StatementSide(id, rs.getInt("line_number"), rs.getString("bank_reference"),
                rs.getBigDecimal("bank_amount"), rs.getObject("bank_value_date", LocalDate.class),
                rs.getString("bank_description"));
    }

    private static List<Integer> copyLineNumbers(ResultSet rs) throws SQLException {
        String lineNumbers = rs.getString("copy_line_numbers");
        if (lineNumbers == null) {
            return List.of();
        }
        return Arrays.stream(lineNumbers.split(",")).map(Integer::valueOf).toList();
    }
}
