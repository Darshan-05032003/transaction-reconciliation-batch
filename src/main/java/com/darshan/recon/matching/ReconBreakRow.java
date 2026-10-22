package com.darshan.recon.matching;

import com.darshan.recon.matching.engine.BreakSeverity;
import java.math.BigDecimal;

public record ReconBreakRow(
        long reconRunId,
        ReconBreakType breakType,
        BreakSeverity severity,
        Long ledgerTxnId,
        Long statementLineId,
        BigDecimal ledgerAmount,
        BigDecimal bankAmount,
        String details) {
}
