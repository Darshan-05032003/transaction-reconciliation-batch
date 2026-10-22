package com.darshan.recon.matching.engine;

import java.math.BigDecimal;
import java.util.List;

public sealed interface MatchOutcome {

    record Matched(LedgerSide ledger, StatementSide statement) implements MatchOutcome {
    }

    record AmountMismatch(LedgerSide ledger, StatementSide statement, BigDecimal difference, BigDecimal tolerance)
            implements MatchOutcome {
    }

    record ValueDateMismatch(LedgerSide ledger, StatementSide statement, long daysApart, int toleranceDays)
            implements MatchOutcome {

        public boolean withinTolerance() {
            return Math.abs(daysApart) <= toleranceDays;
        }

        public BreakSeverity severity() {
            return withinTolerance() ? BreakSeverity.WARNING : BreakSeverity.ERROR;
        }
    }

    record MissingInBank(LedgerSide ledger) implements MatchOutcome {
    }

    record MissingInLedger(StatementSide statement) implements MatchOutcome {
    }

    // ledger is null when the duplicated reference has no ledger transaction at all.
    record DuplicateInStatement(StatementSide firstCopy, LedgerSide ledger, List<Integer> lineNumbers)
            implements MatchOutcome {

        public int copies() {
            return lineNumbers.size();
        }
    }
}
