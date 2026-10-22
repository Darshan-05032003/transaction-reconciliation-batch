package com.darshan.recon.ingest.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ControlTotalsTest {

    @Test
    void countsEveryDetailLineAndSumsValidAmounts() {
        ControlTotals totals = new ControlTotals();

        totals.add("DTL,REF-1,2026-10-01,100.00,INR,clean");
        totals.add("DTL,REF-2,2026-10-01,-25.50,INR,reversal");
        totals.add("DTL,REF-3,2026-10-01,12.5O,INR,bad amount");
        totals.add("DTL,REF-4,bad-date,10.00,INR,bad date");
        totals.add("DTL,REF-5,2026-10-01,5.00,INR");

        assertThat(totals.recordCount()).isEqualTo(5);
        assertThat(totals.totalAmount()).isEqualByComparingTo("89.50");
    }

    @Test
    void startsFromZeroWithScaleTwo() {
        ControlTotals totals = new ControlTotals();

        assertThat(totals.recordCount()).isZero();
        assertThat(totals.totalAmount()).isEqualByComparingTo("0.00").hasScaleOf(2);
        assertThat(StatementRecordFormat.formatTrailer(totals.toTrailer())).isEqualTo("TRL,0,0.00");
    }

    @Test
    void matchesTrailerByCountAndNumericValue() {
        ControlTotals totals = new ControlTotals();
        totals.add("DTL,REF-1,2026-10-01,100.00,INR,a");
        totals.add("DTL,REF-2,2026-10-01,0.10,INR,b");

        assertThat(totals.matches(new StatementTrailer(2, new BigDecimal("100.1")))).isTrue();
        assertThat(totals.matches(new StatementTrailer(3, new BigDecimal("100.10")))).isFalse();
        assertThat(totals.matches(new StatementTrailer(2, new BigDecimal("100.11")))).isFalse();
    }

    @Test
    void rejectsNonDetailLines() {
        ControlTotals totals = new ControlTotals();

        assertThatThrownBy(() -> totals.add("TRL,1,100.00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
