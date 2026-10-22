package com.darshan.recon.matching.engine;

import static com.darshan.recon.matching.engine.Sides.DATE;
import static com.darshan.recon.matching.engine.Sides.ledger;
import static com.darshan.recon.matching.engine.Sides.statement;
import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.matching.engine.MatchOutcome.ValueDateMismatch;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ValueDateMismatchRuleTest {

    private final ValueDateMismatchRule rule = new ValueDateMismatchRule(3);

    @Test
    void sameValueDateIsNotAMismatch() {
        assertThat(rule.evaluate(CandidatePair.paired(ledger("TXN-1", "10.00"), statement("TXN-1", "10.00")))).isEmpty();
    }

    @ParameterizedTest(name = "bank value date shifted by {0} days")
    @CsvSource({"1, WARNING", "-1, WARNING", "3, WARNING", "-3, WARNING", "4, ERROR", "-4, ERROR", "30, ERROR"})
    void shiftedValueDateIsAMismatchWhoseSeverityDependsOnTheTolerance(int shift, BreakSeverity severity) {
        Optional<MatchOutcome> outcome = rule.evaluate(CandidatePair.paired(
                ledger("TXN-1", "10.00"), statement("TXN-1", "10.00", DATE.plusDays(shift))));

        assertThat(outcome).get().isInstanceOfSatisfying(ValueDateMismatch.class, mismatch -> {
            assertThat(mismatch.daysApart()).isEqualTo(shift);
            assertThat(mismatch.toleranceDays()).isEqualTo(3);
            assertThat(mismatch.withinTolerance()).isEqualTo(severity == BreakSeverity.WARNING);
            assertThat(mismatch.severity()).isEqualTo(severity);
        });
    }

    @Test
    void oneSidedPairsAreNotThisRulesBusiness() {
        assertThat(rule.evaluate(CandidatePair.ledgerOnly(ledger("TXN-1", "10.00")))).isEmpty();
        assertThat(rule.evaluate(CandidatePair.statementOnly(statement("TXN-1", "10.00")))).isEmpty();
    }
}
