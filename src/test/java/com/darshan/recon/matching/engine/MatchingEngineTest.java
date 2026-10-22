package com.darshan.recon.matching.engine;

import static com.darshan.recon.matching.engine.Sides.DATE;
import static com.darshan.recon.matching.engine.Sides.ledger;
import static com.darshan.recon.matching.engine.Sides.statement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.recon.matching.engine.MatchOutcome.AmountMismatch;
import com.darshan.recon.matching.engine.MatchOutcome.DuplicateInStatement;
import com.darshan.recon.matching.engine.MatchOutcome.Matched;
import com.darshan.recon.matching.engine.MatchOutcome.MissingInBank;
import com.darshan.recon.matching.engine.MatchOutcome.MissingInLedger;
import com.darshan.recon.matching.engine.MatchOutcome.ValueDateMismatch;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MatchingEngineTest {

    private final MatchingEngine engine = MatchingEngine.standard(new BigDecimal("0.00"), 3);

    @ParameterizedTest(name = "ledger ''{0}'' {1} matches bank ''{2}'' {3}")
    @CsvSource(delimiter = '|', value = {
            "TXN-1      | 100.00  | TXN-1       | 100.00",
            "TXN-1      | 100.00  | TXN-1       | 100.0",
            "TXN-1      | -450.00 | TXN-1       | -450.00",
            "TXN-1      | 100.00  | txn-1       | 100.00",
            "TXN-1      | 100.00  | '  TXN-1  ' | 100.00",
            "'TXN 1'    | 100.00  | 'txn   1'   | 100.00"})
    void sameReferenceAmountAndValueDateIsAMatch(String ledgerRef, String ledgerAmount, String bankRef, String bankAmount) {
        LedgerSide ledger = new LedgerSide(1L, ledgerRef, new BigDecimal(ledgerAmount), DATE, null);
        StatementSide statement = new StatementSide(2L, 3, bankRef, new BigDecimal(bankAmount), DATE, null);

        assertThat(engine.classify(CandidatePair.paired(ledger, statement))).isEqualTo(new Matched(ledger, statement));
    }

    @Test
    void duplicateWinsOverEveryOtherDifference() {
        CandidatePair pair = new CandidatePair(
                ledger("TXN-1", "100.00"), statement("TXN-1", "250.00", DATE.plusDays(9)), List.of(3, 4));

        assertThat(engine.classify(pair)).isInstanceOf(DuplicateInStatement.class);
    }

    @Test
    void missingSidesAreNeverMismatches() {
        assertThat(engine.classify(CandidatePair.ledgerOnly(ledger("TXN-1", "100.00")))).isInstanceOf(MissingInBank.class);
        assertThat(engine.classify(CandidatePair.statementOnly(statement("TXN-1", "100.00")))).isInstanceOf(MissingInLedger.class);
    }

    @Test
    void amountDifferenceWinsOverValueDateDifference() {
        CandidatePair pair = CandidatePair.paired(ledger("TXN-1", "100.00"), statement("TXN-1", "100.50", DATE.plusDays(1)));

        assertThat(engine.classify(pair)).isInstanceOf(AmountMismatch.class);
    }

    @Test
    void valueDateDifferenceAloneIsAValueDateMismatch() {
        CandidatePair pair = CandidatePair.paired(ledger("TXN-1", "100.00"), statement("TXN-1", "100.00", DATE.minusDays(2)));

        assertThat(engine.classify(pair)).isInstanceOfSatisfying(ValueDateMismatch.class,
                mismatch -> assertThat(mismatch.daysApart()).isEqualTo(-2));
    }

    @Test
    void amountToleranceTurnsSmallDifferencesIntoMatches() {
        MatchingEngine tolerant = MatchingEngine.standard(new BigDecimal("1.00"), 3);
        CandidatePair pair = CandidatePair.paired(ledger("TXN-1", "100.00"), statement("TXN-1", "100.99"));

        assertThat(tolerant.classify(pair)).isInstanceOf(Matched.class);
        assertThat(engine.classify(pair)).isInstanceOf(AmountMismatch.class);
    }

    @Test
    void anEngineWithoutRulesForOneSidedPairsFailsLoudly() {
        MatchingEngine amountOnly = new MatchingEngine(List.of(new AmountMismatchRule(BigDecimal.ZERO)));

        assertThatThrownBy(() -> amountOnly.classify(CandidatePair.ledgerOnly(ledger("TXN-1", "1.00"))))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("statement side");
    }
}
