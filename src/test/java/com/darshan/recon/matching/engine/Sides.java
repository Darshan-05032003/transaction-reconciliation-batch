package com.darshan.recon.matching.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

final class Sides {

    static final LocalDate DATE = LocalDate.of(2026, 10, 1);

    private Sides() {
    }

    static LedgerSide ledger(String reference, String amount) {
        return ledger(reference, amount, DATE);
    }

    static LedgerSide ledger(String reference, String amount, LocalDate valueDate) {
        return new LedgerSide(1L, reference, new BigDecimal(amount), valueDate, "UPI/Ravi Stores");
    }

    static StatementSide statement(String reference, String amount) {
        return statement(reference, amount, DATE);
    }

    static StatementSide statement(String reference, String amount, LocalDate valueDate) {
        return new StatementSide(10L, 7, reference, new BigDecimal(amount), valueDate, null);
    }
}
