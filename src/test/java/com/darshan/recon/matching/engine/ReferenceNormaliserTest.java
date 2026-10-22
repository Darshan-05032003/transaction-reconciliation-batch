package com.darshan.recon.matching.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReferenceNormaliserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "TXN-001            | TXN-001",
            "txn-001            | TXN-001",
            "'  TXN-001  '      | TXN-001",
            "'\tTXN-001\n'      | TXN-001",
            "'TXN   001'        | TXN 001",
            "' txn \t 001 '     | TXN 001",
            "'UPI/ravi 2026'    | UPI/RAVI 2026"})
    void trimsCollapsesWhitespaceAndUpperCases(String raw, String expected) {
        assertThat(ReferenceNormaliser.normalise(raw)).isEqualTo(expected);
    }

    @Test
    void sqlExpressionWrapsTheGivenColumn() {
        assertThat(ReferenceNormaliser.sql("bank_reference"))
                .isEqualTo("upper(btrim(regexp_replace(bank_reference, '\\s+', ' ', 'g')))");
    }
}
