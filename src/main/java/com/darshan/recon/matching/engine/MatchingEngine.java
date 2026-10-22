package com.darshan.recon.matching.engine;

import com.darshan.recon.matching.engine.MatchOutcome.Matched;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Applies the rules in order and returns the first outcome; a pair no rule claims is a match.
public final class MatchingEngine {

    private final List<MatchingRule> rules;

    public MatchingEngine(List<MatchingRule> rules) {
        this.rules = List.copyOf(rules);
    }

    // Order matters: a duplicated reference is reported once as a duplicate even if its
    // copies also differ in amount, and a one-sided pair is missing, never mismatched.
    public static MatchingEngine standard(BigDecimal amountTolerance, int dateToleranceDays) {
        return new MatchingEngine(List.of(
                new DuplicateInStatementRule(),
                new MissingInLedgerRule(),
                new MissingInBankRule(),
                new AmountMismatchRule(amountTolerance),
                new ValueDateMismatchRule(dateToleranceDays)));
    }

    public MatchOutcome classify(CandidatePair pair) {
        for (MatchingRule rule : rules) {
            Optional<MatchOutcome> outcome = rule.evaluate(pair);
            if (outcome.isPresent()) {
                return outcome.get();
            }
        }
        return new Matched(
                Objects.requireNonNull(pair.ledger(), "no rule claimed a pair without a ledger side"),
                Objects.requireNonNull(pair.statement(), "no rule claimed a pair without a statement side"));
    }
}
