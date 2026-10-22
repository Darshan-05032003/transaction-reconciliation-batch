package com.darshan.recon.matching;

import java.math.BigDecimal;

public record ReconBreakRow(
        long reconRunId,
        ReconBreakType breakType,
        Long ledgerTxnId,
        Long statementLineId,
        BigDecimal ledgerAmount,
        BigDecimal bankAmount,
        String details) {
}
