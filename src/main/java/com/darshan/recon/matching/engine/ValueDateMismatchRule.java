package com.darshan.recon.matching.engine;

import com.darshan.recon.matching.engine.MatchOutcome.ValueDateMismatch;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

final class ValueDateMismatchRule implements MatchingRule {

    private final int toleranceDays;

    ValueDateMismatchRule(int toleranceDays) {
        if (toleranceDays < 0) {
            throw new IllegalArgumentException("date tolerance must not be negative: " + toleranceDays);
        }
        this.toleranceDays = toleranceDays;
    }

    @Override
    public Optional<MatchOutcome> evaluate(CandidatePair pair) {
        if (pair.ledger() == null || pair.statement() == null) {
            return Optional.empty();
        }
        long daysApart = ChronoUnit.DAYS.between(pair.ledger().valueDate(), pair.statement().valueDate());
        if (daysApart == 0) {
            return Optional.empty();
        }
        return Optional.of(new ValueDateMismatch(pair.ledger(), pair.statement(), daysApart, toleranceDays));
    }
}
