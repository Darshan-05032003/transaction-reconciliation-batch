package com.darshan.recon.ingest.file;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Every DTL record counts; the total sums the amount field of the DTL records whose amount
// is valid. The generator builds trailers with this and ingestion recomputes them with it,
// so both sides treat a malformed line the same way.
public final class ControlTotals {

    private int recordCount;
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(StatementRecordFormat.AMOUNT_SCALE, RoundingMode.UNNECESSARY);

    public void add(String detailLine) {
        if (!StatementRecordFormat.isDetail(detailLine)) {
            throw new IllegalArgumentException("not a DTL record: " + detailLine);
        }
        recordCount++;
        BigDecimal amount = StatementRecordFormat.detailAmountOrNull(detailLine);
        if (amount != null) {
            totalAmount = totalAmount.add(amount);
        }
    }

    public int recordCount() {
        return recordCount;
    }

    public BigDecimal totalAmount() {
        return totalAmount;
    }

    public StatementTrailer toTrailer() {
        return new StatementTrailer(recordCount, totalAmount);
    }

    public boolean matches(StatementTrailer trailer) {
        return trailer.recordCount() == recordCount && trailer.totalAmount().compareTo(totalAmount) == 0;
    }
}
