package com.darshan.recon.matching.engine;

import java.util.List;

// One row of the pairing query: a ledger transaction and the first statement line with the
// same normalised reference, either of which may be absent, plus the line numbers of every
// copy of that reference in the statement.
public record CandidatePair(LedgerSide ledger, StatementSide statement, List<Integer> statementLineNumbers) {

    public CandidatePair {
        if (ledger == null && statement == null) {
            throw new IllegalArgumentException("a candidate pair needs at least one side");
        }
        if (ledger != null && statement != null
                && !ReferenceNormaliser.normalise(ledger.reference()).equals(
                        ReferenceNormaliser.normalise(statement.reference()))) {
            throw new IllegalArgumentException("references differ after normalisation: '%s' vs '%s'"
                    .formatted(ledger.reference(), statement.reference()));
        }
        statementLineNumbers = statement == null ? List.of() : List.copyOf(statementLineNumbers);
        if (statement != null && statementLineNumbers.isEmpty()) {
            throw new IllegalArgumentException("a statement side needs at least its own line number");
        }
    }

    public static CandidatePair paired(LedgerSide ledger, StatementSide statement) {
        return new CandidatePair(ledger, statement, List.of(statement.lineNumber()));
    }

    public static CandidatePair ledgerOnly(LedgerSide ledger) {
        return new CandidatePair(ledger, null, List.of());
    }

    public static CandidatePair statementOnly(StatementSide statement) {
        return new CandidatePair(null, statement, List.of(statement.lineNumber()));
    }

    public int statementCopies() {
        return statementLineNumbers.size();
    }
}
