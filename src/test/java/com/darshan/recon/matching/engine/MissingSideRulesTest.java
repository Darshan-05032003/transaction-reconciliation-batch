package com.darshan.recon.matching.engine;

import static com.darshan.recon.matching.engine.Sides.ledger;
import static com.darshan.recon.matching.engine.Sides.statement;
import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.matching.engine.MatchOutcome.MissingInBank;
import com.darshan.recon.matching.engine.MatchOutcome.MissingInLedger;
import org.junit.jupiter.api.Test;

class MissingSideRulesTest {

    private final MissingInBankRule missingInBank = new MissingInBankRule();
    private final MissingInLedgerRule missingInLedger = new MissingInLedgerRule();

    @Test
    void ledgerTransactionWithoutAStatementLineIsMissingInBank() {
        LedgerSide ledger = ledger("TXN-1", "10.00");

        assertThat(missingInBank.evaluate(CandidatePair.ledgerOnly(ledger)))
                .contains(new MissingInBank(ledger));
        assertThat(missingInLedger.evaluate(CandidatePair.ledgerOnly(ledger))).isEmpty();
    }

    @Test
    void statementLineWithoutALedgerTransactionIsMissingInLedger() {
        StatementSide statement = statement("TXN-1", "10.00");

        assertThat(missingInLedger.evaluate(CandidatePair.statementOnly(statement)))
                .contains(new MissingInLedger(statement));
        assertThat(missingInBank.evaluate(CandidatePair.statementOnly(statement))).isEmpty();
    }

    @Test
    void twoSidedPairsAreLeftToTheOtherRules() {
        CandidatePair pair = CandidatePair.paired(ledger("TXN-1", "10.00"), statement("TXN-1", "99.00"));

        assertThat(missingInBank.evaluate(pair)).isEmpty();
        assertThat(missingInLedger.evaluate(pair)).isEmpty();
    }
}
