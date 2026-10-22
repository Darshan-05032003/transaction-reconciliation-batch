package com.darshan.recon.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.recon.ingest.file.MalformedRecordException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StatementLineProcessorTest {

    private final StatementLineProcessor processor = new StatementLineProcessor(42L, "000123456789");

    @Test
    void parsesAValidLineIntoARowForTheStatementFile() {
        StatementLineRow row = processor.process(
                new RawStatementLine(7, "DTL,TXN-1,2026-10-01,1250.5,INR,NEFT/Ravi Stores"));

        assertThat(row.statementFileId()).isEqualTo(42L);
        assertThat(row.lineNumber()).isEqualTo(7);
        assertThat(row.bankReference()).isEqualTo("TXN-1");
        assertThat(row.accountNumber()).isEqualTo("000123456789");
        assertThat(row.amount()).isEqualByComparingTo("1250.50");
        assertThat(row.amount().scale()).isEqualTo(2);
        assertThat(row.currency()).isEqualTo("INR");
        assertThat(row.valueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(row.description()).isEqualTo("NEFT/Ravi Stores");
    }

    @Test
    void keepsNegativeAmountsAndStoresBlankDescriptionsAsNull() {
        StatementLineRow row = processor.process(new RawStatementLine(3, "DTL,TXN-2,2026-10-01,-30.00,INR,"));

        assertThat(row.amount()).isEqualByComparingTo("-30.00");
        assertThat(row.description()).isNull();
    }

    @Test
    void rejectsMalformedLinesWithTheReason() {
        assertThatThrownBy(() -> processor.process(new RawStatementLine(5, "DTL,TXN-3,2026-10-01,12.5O,INR,UPI/Bakery")))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> processor.process(new RawStatementLine(6, "DTL,TXN-4,2026-10-01,40.00,INR")))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("expected 6 fields");
        assertThatThrownBy(() -> processor.process(new RawStatementLine(7, "DTL,TXN-5,02/10/2026,60.10,INR,POS/Shop")))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("valueDate");
        assertThatThrownBy(() -> processor.process(new RawStatementLine(8, "DTL,TXN-6,2026-10-01,60.10,inr,POS/Shop")))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> processor.process(new RawStatementLine(9, "")))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("expected a DTL record");
    }

    @Test
    void rejectsValuesThatWouldNotFitTheirColumns() {
        String longReference = "R".repeat(StatementLineProcessor.MAX_REFERENCE_LENGTH + 1);
        String longDescription = "D".repeat(StatementLineProcessor.MAX_DESCRIPTION_LENGTH + 1);

        assertThatThrownBy(() -> processor.process(
                new RawStatementLine(2, "DTL," + longReference + ",2026-10-01,10.00,INR,ok")))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("bankReference");
        assertThatThrownBy(() -> processor.process(
                new RawStatementLine(3, "DTL,TXN-7,2026-10-01,10.00,INR," + longDescription)))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("description");
    }
}
