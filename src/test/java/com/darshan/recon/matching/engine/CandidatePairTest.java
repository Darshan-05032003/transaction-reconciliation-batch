package com.darshan.recon.matching.engine;

import static com.darshan.recon.matching.engine.Sides.ledger;
import static com.darshan.recon.matching.engine.Sides.statement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CandidatePairTest {

    @Test
    void acceptsSidesWhoseReferencesDifferOnlyInCaseOrWhitespace() {
        CandidatePair pair = CandidatePair.paired(ledger("TXN-001", "10.00"), statement("  txn-001 ", "10.00"));

        assertThat(pair.statementCopies()).isEqualTo(1);
        assertThat(pair.statementLineNumbers()).containsExactly(7);
    }

    @Test
    void rejectsSidesWithDifferentReferences() {
        assertThatThrownBy(() -> CandidatePair.paired(ledger("TXN-001", "10.00"), statement("TXN-002", "10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TXN-001")
                .hasMessageContaining("TXN-002");
    }

    @Test
    void rejectsAPairWithoutAnySide() {
        assertThatThrownBy(() -> new CandidatePair(null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAStatementSideWithoutLineNumbers() {
        assertThatThrownBy(() -> new CandidatePair(null, statement("TXN-001", "10.00"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ledgerOnlyPairHasNoStatementCopies() {
        CandidatePair pair = CandidatePair.ledgerOnly(ledger("TXN-001", "10.00"));

        assertThat(pair.statement()).isNull();
        assertThat(pair.statementCopies()).isZero();
    }
}
