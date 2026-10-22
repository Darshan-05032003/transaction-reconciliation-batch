package com.darshan.recon.matching.engine;

import com.darshan.recon.matching.engine.MatchOutcome.DuplicateInStatement;
import java.util.Optional;

final class DuplicateInStatementRule implements MatchingRule {

    @Override
    public Optional<MatchOutcome> evaluate(CandidatePair pair) {
        if (pair.statement() == null || pair.statementCopies() < 2) {
            return Optional.empty();
        }
        return Optional.of(new DuplicateInStatement(pair.statement(), pair.ledger(), pair.statementLineNumbers()));
    }
}
