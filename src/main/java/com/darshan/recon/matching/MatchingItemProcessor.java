package com.darshan.recon.matching;

import com.darshan.recon.matching.engine.CandidatePair;
import com.darshan.recon.matching.engine.LedgerSide;
import com.darshan.recon.matching.engine.MatchOutcome.AmountMismatch;
import com.darshan.recon.matching.engine.MatchOutcome.DuplicateInStatement;
import com.darshan.recon.matching.engine.MatchOutcome.Matched;
import com.darshan.recon.matching.engine.MatchOutcome.MissingInBank;
import com.darshan.recon.matching.engine.MatchOutcome.MissingInLedger;
import com.darshan.recon.matching.engine.MatchOutcome.ValueDateMismatch;
import com.darshan.recon.matching.engine.MatchingEngine;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.batch.infrastructure.item.ItemProcessor;

// Classifies a candidate pair and turns every non-match into a recon_break row. A match
// returns null, which Spring Batch counts as filtered and never hands to the writer.
public class MatchingItemProcessor implements ItemProcessor<CandidatePair, ReconBreakRow> {

    static final int MAX_DETAILS_LENGTH = 500;

    private final MatchingEngine engine;
    private final long reconRunId;

    public MatchingItemProcessor(MatchingEngine engine, long reconRunId) {
        this.engine = engine;
        this.reconRunId = reconRunId;
    }

    @Override
    public ReconBreakRow process(CandidatePair pair) {
        return switch (engine.classify(pair)) {
            case Matched ignored -> null;
            case AmountMismatch mismatch -> row(ReconBreakType.AMOUNT_MISMATCH,
                    mismatch.ledger().id(), mismatch.statement().id(),
                    mismatch.ledger().amount(), mismatch.statement().amount(),
                    "amount differs by %s (ledger %s, bank %s, tolerance %s)".formatted(
                            plain(mismatch.difference()), plain(mismatch.ledger().amount()),
                            plain(mismatch.statement().amount()), plain(mismatch.tolerance())));
            case ValueDateMismatch mismatch -> row(ReconBreakType.VALUE_DATE_MISMATCH,
                    mismatch.ledger().id(), mismatch.statement().id(),
                    mismatch.ledger().amount(), mismatch.statement().amount(),
                    "value date differs by %d day(s) (ledger %s, bank %s), %s tolerance of %d day(s): %s".formatted(
                            mismatch.daysApart(), mismatch.ledger().valueDate(), mismatch.statement().valueDate(),
                            mismatch.withinTolerance() ? "within" : "outside", mismatch.toleranceDays(),
                            mismatch.severity().name().toLowerCase(Locale.ROOT)));
            case MissingInBank missing -> row(ReconBreakType.MISSING_IN_BANK,
                    missing.ledger().id(), null, missing.ledger().amount(), null,
                    "ledger transaction %s with value date %s has no statement line".formatted(
                            missing.ledger().reference(), missing.ledger().valueDate()));
            case MissingInLedger missing -> row(ReconBreakType.MISSING_IN_LEDGER,
                    null, missing.statement().id(), null, missing.statement().amount(),
                    "statement line %d (%s) has no ledger transaction".formatted(
                            missing.statement().lineNumber(), missing.statement().reference()));
            case DuplicateInStatement duplicate -> row(ReconBreakType.DUPLICATE_IN_STATEMENT,
                    duplicate.ledger() == null ? null : duplicate.ledger().id(), duplicate.firstCopy().id(),
                    duplicate.ledger() == null ? null : duplicate.ledger().amount(), duplicate.firstCopy().amount(),
                    duplicateDetails(duplicate));
        };
    }

    private static String duplicateDetails(DuplicateInStatement duplicate) {
        String lines = duplicate.lineNumbers().stream().map(String::valueOf).collect(Collectors.joining(", "));
        LedgerSide ledger = duplicate.ledger();
        return "reference %s appears %d times in the statement (lines %s); %s".formatted(
                duplicate.firstCopy().reference(), duplicate.copies(), lines,
                ledger == null ? "no ledger transaction" : "ledger transaction " + ledger.reference() + " left unmatched");
    }

    private ReconBreakRow row(ReconBreakType type, Long ledgerTxnId, Long statementLineId,
                              BigDecimal ledgerAmount, BigDecimal bankAmount, String details) {
        return new ReconBreakRow(reconRunId, type, ledgerTxnId, statementLineId, ledgerAmount, bankAmount,
                details.length() <= MAX_DETAILS_LENGTH ? details : details.substring(0, MAX_DETAILS_LENGTH));
    }

    private static String plain(BigDecimal amount) {
        return amount.toPlainString();
    }
}
