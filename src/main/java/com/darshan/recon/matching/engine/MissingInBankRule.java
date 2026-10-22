package com.darshan.recon.matching.engine;

import com.darshan.recon.matching.engine.MatchOutcome.MissingInBank;
import java.util.Optional;

final class MissingInBankRule implements MatchingRule {

    @Override
    public Optional<MatchOutcome> evaluate(CandidatePair pair) {
        if (pair.statement() != null || pair.ledger() == null) {
            return Optional.empty();
        }
        return Optional.of(new MissingInBank(pair.ledger()));
    }
}
