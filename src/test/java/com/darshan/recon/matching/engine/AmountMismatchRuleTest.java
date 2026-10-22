package com.darshan.recon.matching.engine;

import static com.darshan.recon.matching.engine.Sides.ledger;
import static com.darshan.recon.matching.engine.Sides.statement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.recon.matching.engine.MatchOutcome.AmountMismatch;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmountMismatchRuleTest {

    @ParameterizedTest(name = "ledger {0}, bank {1}, tolerance {2}")
    @CsvSource({
            "100.00, 100.00, 0.00",
            "100.00, 100.0, 0.00",
            "100, 100.000, 0.00",
            "-450.00, -450.00, 0.00",
            "100.00, 100.01, 0.01",
            "100.00, 99.99, 0.01",
            "2500.50, 2501.00, 1.00"})
    void amountsWithinTheToleranceAreNotAMismatch(String ledgerAmount, String bankAmount, String tolerance) {
        AmountMismatchRule rule = new AmountMismatchRule(new BigDecimal(tolerance));

        Optional<MatchOutcome> outcome = rule.evaluate(
                CandidatePair.paired(ledger("TXN-1", ledgerAmount), statement("TXN-1", bankAmount)));

        assertThat(outcome).isEmpty();
    }

    @ParameterizedTest(name = "ledger {0}, bank {1}, tolerance {2} differs by {3}")
    @CsvSource({
            "100.00, 100.01, 0.00, 0.01",
            "100.00, 99.99, 0.00, -0.01",
            "100.00, 100.02, 0.01, 0.02",
            "450.00, -450.00, 0.00, -900.00",
            "-450.00, 450.00, 0.00, 900.00",
            "2500.50, 2502.00, 1.00, 1.50"})
    void amountsOutsideTheToleranceAreAMismatchWithTheSignedDifference(
            String ledgerAmount, String bankAmount, String tolerance, String difference) {
        AmountMismatchRule rule = new AmountMismatchRule(new BigDecimal(tolerance));

        Optional<MatchOutcome> outcome = rule.evaluate(
                CandidatePair.paired(ledger("TXN-1", ledgerAmount), statement("TXN-1", bankAmount)));

        assertThat(outcome).get().isInstanceOfSatisfying(AmountMismatch.class, mismatch -> {
            assertThat(mismatch.difference()).isEqualByComparingTo(difference);
            assertThat(mismatch.tolerance()).isEqualByComparingTo(tolerance);
        });
    }

    @Test
    void oneSidedPairsAreNotThisRulesBusiness() {
        AmountMismatchRule rule = new AmountMismatchRule(BigDecimal.ZERO);

        assertThat(rule.evaluate(CandidatePair.ledgerOnly(ledger("TXN-1", "10.00")))).isEmpty();
        assertThat(rule.evaluate(CandidatePair.statementOnly(statement("TXN-1", "10.00")))).isEmpty();
    }

    @Test
    void rejectsANegativeTolerance() {
        assertThatThrownBy(() -> new AmountMismatchRule(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
