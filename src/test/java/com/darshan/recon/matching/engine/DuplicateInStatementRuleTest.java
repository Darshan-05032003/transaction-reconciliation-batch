package com.darshan.recon.matching.engine;

import static com.darshan.recon.matching.engine.Sides.ledger;
import static com.darshan.recon.matching.engine.Sides.statement;
import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.recon.matching.engine.MatchOutcome.DuplicateInStatement;
import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicateInStatementRuleTest {

    private final DuplicateInStatementRule rule = new DuplicateInStatementRule();

    @Test
    void singleCopyIsNotADuplicate() {
        assertThat(rule.evaluate(CandidatePair.paired(ledger("TXN-1", "10.00"), statement("TXN-1", "10.00")))).isEmpty();
        assertThat(rule.evaluate(CandidatePair.statementOnly(statement("TXN-1", "10.00")))).isEmpty();
        assertThat(rule.evaluate(CandidatePair.ledgerOnly(ledger("TXN-1", "10.00")))).isEmpty();
    }

    @Test
    void repeatedReferenceWithALedgerTransactionIsReportedOnceWithEveryLine() {
        LedgerSide ledger = ledger("TXN-1", "10.00");
        StatementSide firstCopy = statement("TXN-1", "10.00");

        assertThat(rule.evaluate(new CandidatePair(ledger, firstCopy, List.of(7, 240))))
                .contains(new DuplicateInStatement(firstCopy, ledger, List.of(7, 240)));
    }

    @Test
    void repeatedReferenceWithoutALedgerTransactionIsStillADuplicate() {
        StatementSide firstCopy = statement("TXN-9", "10.00");

        assertThat(rule.evaluate(new CandidatePair(null, firstCopy, List.of(7, 8, 9))))
                .get().isInstanceOfSatisfying(DuplicateInStatement.class, duplicate -> {
                    assertThat(duplicate.ledger()).isNull();
                    assertThat(duplicate.copies()).isEqualTo(3);
                });
    }
}
