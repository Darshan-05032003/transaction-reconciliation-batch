package com.darshan.recon.resolution;

import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.engine.BreakSeverity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record BreakView(
        long id,
        long reconRunId,
        ReconBreakType breakType,
        BreakSeverity severity,
        Long ledgerTxnId,
        Long statementLineId,
        BigDecimal ledgerAmount,
        BigDecimal bankAmount,
        BigDecimal exposure,
        String details,
        BreakStatus status,
        String resolvedBy,
        OffsetDateTime resolvedAt,
        String resolutionComment,
        String requestedBy,
        OffsetDateTime requestedAt,
        String approvedBy,
        OffsetDateTime approvedAt,
        List<ActionView> actions) {

    public record ActionView(BreakActionType action, String performedBy, OffsetDateTime performedAt, String comment) {

        static ActionView of(BreakAction action) {
            return new ActionView(action.getAction(), action.getPerformedBy(), action.getPerformedAt(),
                    action.getComment());
        }
    }

    static BreakView of(ReconBreak b, List<BreakAction> actions) {
        return new BreakView(b.getId(), b.getReconRunId(), b.getBreakType(), b.getSeverity(), b.getLedgerTxnId(),
                b.getStatementLineId(), b.getLedgerAmount(), b.getBankAmount(), b.exposure(), b.getDetails(),
                b.getStatus(), b.getResolvedBy(), b.getResolvedAt(), b.getResolutionComment(), b.getRequestedBy(),
                b.getRequestedAt(), b.getApprovedBy(), b.getApprovedAt(),
                actions.stream().map(ActionView::of).toList());
    }
}
