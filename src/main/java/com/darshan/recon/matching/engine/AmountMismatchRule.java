package com.darshan.recon.matching.engine;

import com.darshan.recon.matching.engine.MatchOutcome.AmountMismatch;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

final class AmountMismatchRule implements MatchingRule {

    private final BigDecimal tolerance;

    AmountMismatchRule(BigDecimal tolerance) {
        Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.signum() < 0) {
            throw new IllegalArgumentException("amount tolerance must not be negative: " + tolerance);
        }
        this.tolerance = tolerance;
    }

    // compareTo, not equals: 100.0 and 100.00 are the same amount. Signs are compared as
    // they are, so a reversal booked as -450.00 does not match a credit of 450.00.
    @Override
    public Optional<MatchOutcome> evaluate(CandidatePair pair) {
        if (pair.ledger() == null || pair.statement() == null) {
            return Optional.empty();
        }
        BigDecimal difference = pair.statement().amount().subtract(pair.ledger().amount());
        if (difference.abs().compareTo(tolerance) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new AmountMismatch(pair.ledger(), pair.statement(), difference, tolerance));
    }
}
