package com.darshan.recon.matching.engine;

import com.darshan.recon.matching.engine.MatchOutcome.MissingInLedger;
import java.util.Optional;

final class MissingInLedgerRule implements MatchingRule {

    @Override
    public Optional<MatchOutcome> evaluate(CandidatePair pair) {
        if (pair.ledger() != null || pair.statement() == null) {
            return Optional.empty();
        }
        return Optional.of(new MissingInLedger(pair.statement()));
    }
}
