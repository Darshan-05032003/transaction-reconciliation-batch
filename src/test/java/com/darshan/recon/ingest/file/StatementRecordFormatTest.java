package com.darshan.recon.ingest.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StatementRecordFormatTest {

    @Test
    void formatsAndParsesHeader() {
        StatementHeader header = new StatementHeader("BANK01", "000123456789", LocalDate.of(2026, 10, 1));

        String line = StatementRecordFormat.formatHeader(header);

        assertThat(line).isEqualTo("HDR,BANK01,000123456789,2026-10-01");
        assertThat(StatementRecordFormat.parseHeader(line)).isEqualTo(header);
    }

    @Test
    void formatsAndParsesDetailWithTwoDecimalPlaces() {
        StatementDetail detail = new StatementDetail(
                "TXN20261001-000123456789-0000001", LocalDate.of(2026, 10, 1),
                new BigDecimal("1250.5"), "INR", "UPI/Ravi Stores");

        String line = StatementRecordFormat.formatDetail(detail);

        assertThat(line).isEqualTo("DTL,TXN20261001-000123456789-0000001,2026-10-01,1250.50,INR,UPI/Ravi Stores");
        StatementDetail parsed = StatementRecordFormat.parseDetail(line);
        assertThat(parsed.bankReference()).isEqualTo(detail.bankReference());
        assertThat(parsed.valueDate()).isEqualTo(detail.valueDate());
        assertThat(parsed.amount()).isEqualByComparingTo("1250.50").hasScaleOf(2);
        assertThat(parsed.currency()).isEqualTo("INR");
        assertThat(parsed.description()).isEqualTo("UPI/Ravi Stores");
    }

    @Test
    void formatsNegativeAmountsAndEmptyDescription() {
        StatementDetail reversal = new StatementDetail(
                "REF-1", LocalDate.of(2026, 10, 2), new BigDecimal("-99.99"), "INR", "");

        String line = StatementRecordFormat.formatDetail(reversal);

        assertThat(line).isEqualTo("DTL,REF-1,2026-10-02,-99.99,INR,");
        assertThat(StatementRecordFormat.parseDetail(line).amount()).isEqualByComparingTo("-99.99");
    }

    @Test
    void formatsAndParsesTrailer() {
        StatementTrailer trailer = new StatementTrailer(3, new BigDecimal("1000"));

        String line = StatementRecordFormat.formatTrailer(trailer);

        assertThat(line).isEqualTo("TRL,3,1000.00");
        StatementTrailer parsed = StatementRecordFormat.parseTrailer(line);
        assertThat(parsed.recordCount()).isEqualTo(3);
        assertThat(parsed.totalAmount()).isEqualByComparingTo("1000.00");
    }

    @ParameterizedTest
    @CsvSource({
            "'DTL,REF-1,2026-10-01,12.5O,INR,desc', amount",
            "'DTL,REF-1,2026-10-01,100.005,INR,desc', amount",
            "'DTL,REF-1,2026-10-01,100.00,INR', expected 6 fields but found 5",
            "'DTL,REF-1,2026-10-01,100.00,INR,desc,extra', expected 6 fields but found 7",
            "'DTL,REF-1,01/10/2026,100.00,INR,desc', valueDate",
            "'DTL,REF-1,2026-13-45,100.00,INR,desc', valueDate",
            "'DTL,,2026-10-01,100.00,INR,desc', bankReference is blank",
            "'DTL,REF-1,2026-10-01,100.00,RUPEES,desc', currency",
            "'HDR,REF-1,2026-10-01,100.00,INR,desc', expected a DTL record"
    })
    void rejectsMalformedDetailLines(String line, String expectedReason) {
        assertThatThrownBy(() -> StatementRecordFormat.parseDetail(line))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining(expectedReason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRL,3", "TRL,three,10.00", "TRL,-1,10.00", "TRL,3,ten"})
    void rejectsMalformedTrailers(String line) {
        assertThatThrownBy(() -> StatementRecordFormat.parseTrailer(line))
                .isInstanceOf(MalformedRecordException.class);
    }

    @Test
    void rejectsHeaderWithBlankBankCode() {
        assertThatThrownBy(() -> StatementRecordFormat.parseHeader("HDR, ,000123,2026-10-01"))
                .isInstanceOf(MalformedRecordException.class)
                .hasMessageContaining("bankCode");
    }

    @ParameterizedTest
    @CsvSource({"100, 100.00", "100.0, 100.00", "-0.5, -0.50", " 7.25 , 7.25"})
    void parsesAmountsToScaleTwo(String text, String expected) {
        assertThat(StatementRecordFormat.parseAmount(text))
                .isEqualByComparingTo(expected)
                .hasScaleOf(2);
    }

    @Test
    void detailAmountIsNullWhenMissingOrInvalid() {
        assertThat(StatementRecordFormat.detailAmountOrNull("DTL,REF-1,2026-10-01,12.5O,INR,desc")).isNull();
        assertThat(StatementRecordFormat.detailAmountOrNull("DTL,REF-1,2026-10-01")).isNull();
        assertThat(StatementRecordFormat.detailAmountOrNull("DTL,REF-1,bad-date,45.10,INR"))
                .isEqualByComparingTo("45.10");
    }

    @Test
    void recognisesDetailLinesByTag() {
        assertThat(StatementRecordFormat.isDetail("DTL,REF-1,2026-10-01,1.00,INR,x")).isTrue();
        assertThat(StatementRecordFormat.isDetail("DTLX,REF-1")).isFalse();
        assertThat(StatementRecordFormat.isDetail("HDR,BANK01,1,2026-10-01")).isFalse();
    }
}
