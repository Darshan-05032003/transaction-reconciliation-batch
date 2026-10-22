package com.darshan.recon.report;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.engine.BreakSeverity;
import com.darshan.recon.resolution.BreakStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record BreakReportRow(
        long breakId,
        ReconBreakType breakType,
        BreakSeverity severity,
        String ledgerReference,
        String bankReference,
        Integer lineNumber,
        BigDecimal ledgerAmount,
        BigDecimal bankAmount,
        LocalDate ledgerValueDate,
        LocalDate bankValueDate,
        String details,
        BreakStatus status) {

    public static final String HEADER = "break_id,break_type,severity,ledger_reference,bank_reference,line_number,"
            + "ledger_amount,bank_amount,ledger_value_date,bank_value_date,details,status";

    public String toCsvLine() {
        return CsvFormat.line(breakId, breakType, severity, ledgerReference, bankReference, lineNumber,
                ledgerAmount, bankAmount, ledgerValueDate, bankValueDate, details, status);
    }
}
