package com.darshan.recon.resolution;

import com.darshan.recon.common.exception.BusinessRuleViolationException;
import com.darshan.recon.common.exception.ConflictException;
import com.darshan.recon.matching.ReconBreakType;
import com.darshan.recon.matching.engine.BreakSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// The matching job inserts breaks with JDBC; this entity owns what happens to them afterwards.
// The state machine lives here so that no service can skip a rule: OPEN -> RESOLVED,
// OPEN -> WRITTEN_OFF, or OPEN -> PENDING_APPROVAL -> WRITTEN_OFF (approved) / OPEN (rejected).
@Entity
@Table(name = "recon_break")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconBreak {

    public static final String SELF_APPROVAL = "SELF_APPROVAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recon_run_id", nullable = false, updatable = false)
    private long reconRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "break_type", nullable = false, updatable = false, length = 30)
    private ReconBreakType breakType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private BreakSeverity severity;

    @Column(name = "ledger_txn_id", updatable = false)
    private Long ledgerTxnId;

    @Column(name = "statement_line_id", updatable = false)
    private Long statementLineId;

    @Column(name = "ledger_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal ledgerAmount;

    @Column(name = "bank_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal bankAmount;

    @Column(updatable = false, length = 500)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BreakStatus status;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_comment", length = 500)
    private String resolutionComment;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    ReconBreak(long reconRunId, ReconBreakType breakType, BreakSeverity severity, Long ledgerTxnId,
               Long statementLineId, BigDecimal ledgerAmount, BigDecimal bankAmount, String details) {
        this.reconRunId = reconRunId;
        this.breakType = breakType;
        this.severity = severity;
        this.ledgerTxnId = ledgerTxnId;
        this.statementLineId = statementLineId;
        this.ledgerAmount = ledgerAmount;
        this.bankAmount = bankAmount;
        this.details = details;
        this.status = BreakStatus.OPEN;
    }

    // The amount at stake if the break is simply written off: the difference for a mismatch,
    // the full amount for a one-sided or duplicated transaction.
    public BigDecimal exposure() {
        BigDecimal exposure = switch (breakType) {
            case AMOUNT_MISMATCH -> bankAmount.subtract(ledgerAmount);
            case MISSING_IN_BANK, VALUE_DATE_MISMATCH -> ledgerAmount;
            case MISSING_IN_LEDGER, DUPLICATE_IN_STATEMENT -> bankAmount;
        };
        return exposure.abs().setScale(2, RoundingMode.HALF_EVEN);
    }

    public boolean needsApproval(BigDecimal writeOffApprovalThreshold) {
        return exposure().compareTo(writeOffApprovalThreshold) > 0;
    }

    void resolve(String user, String comment, OffsetDateTime now) {
        requireOpen("resolved");
        status = BreakStatus.RESOLVED;
        resolvedBy = user;
        resolvedAt = now;
        resolutionComment = comment;
    }

    void writeOff(String user, String comment, OffsetDateTime now) {
        requireOpen("written off");
        status = BreakStatus.WRITTEN_OFF;
        resolvedBy = user;
        resolvedAt = now;
        resolutionComment = comment;
    }

    void requestWriteOff(String user, String comment, OffsetDateTime now) {
        requireOpen("written off");
        status = BreakStatus.PENDING_APPROVAL;
        requestedBy = user;
        requestedAt = now;
        resolutionComment = comment;
    }

    // The maker stays the resolver; the checker is recorded separately, which is what an
    // auditor wants to see: who proposed the write-off and who signed it off.
    void approveWriteOff(String approver, OffsetDateTime now) {
        requirePending("approved");
        if (approver.equals(requestedBy)) {
            throw new BusinessRuleViolationException(SELF_APPROVAL,
                    "A write-off cannot be approved by the user who requested it");
        }
        status = BreakStatus.WRITTEN_OFF;
        approvedBy = approver;
        approvedAt = now;
        resolvedBy = requestedBy;
        resolvedAt = now;
    }

    void rejectWriteOff() {
        requirePending("rejected");
        status = BreakStatus.OPEN;
        requestedBy = null;
        requestedAt = null;
        resolutionComment = null;
    }

    private void requireOpen(String verb) {
        if (status != BreakStatus.OPEN) {
            throw new ConflictException("Break %d is %s and cannot be %s".formatted(id, status, verb));
        }
    }

    private void requirePending(String verb) {
        if (status != BreakStatus.PENDING_APPROVAL) {
            throw new ConflictException("Break %d is %s; only a PENDING_APPROVAL write-off can be %s"
                    .formatted(id, status, verb));
        }
    }
}
