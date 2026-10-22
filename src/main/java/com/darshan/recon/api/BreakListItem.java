package com.darshan.recon.api;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.engine.BreakSeverity;
import com.darshan.recon.resolution.BreakStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record BreakListItem(
        long id,
        ReconBreakType breakType,
        BreakSeverity severity,
        Long ledgerTxnId,
        String ledgerReference,
        Long statementLineId,
        String bankReference,
        Integer lineNumber,
        BigDecimal ledgerAmount,
        BigDecimal bankAmount,
        LocalDate ledgerValueDate,
        LocalDate bankValueDate,
        String details,
        BreakStatus status,
        String resolvedBy,
        OffsetDateTime resolvedAt,
        String requestedBy,
        String approvedBy) {
}
