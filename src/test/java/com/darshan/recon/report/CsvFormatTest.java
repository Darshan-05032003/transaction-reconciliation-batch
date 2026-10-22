package com.darshan.recon.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CsvFormatTest {

    @Test
    void leavesPlainFieldsBareAndRendersAmountsWithoutExponent() {
        assertThat(CsvFormat.line(7L, "TXN-1", new BigDecimal("1E+3"), LocalDate.of(2026, 10, 1), null))
                .isEqualTo("7,TXN-1,1000,2026-10-01,");
    }

    @Test
    void quotesFieldsContainingDelimitersQuotesOrLineBreaks() {
        assertThat(CsvFormat.field("amount differs by -1.50 (ledger 100.00, bank 98.50)"))
                .isEqualTo("\"amount differs by -1.50 (ledger 100.00, bank 98.50)\"");
        assertThat(CsvFormat.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvFormat.field("two\nlines")).isEqualTo("\"two\nlines\"");
    }
}
