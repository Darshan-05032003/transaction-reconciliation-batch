package com.darshan.recon.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StatementLineRow(
        long statementFileId,
        int lineNumber,
        String bankReference,
        String accountNumber,
        BigDecimal amount,
        String currency,
        LocalDate valueDate,
        String description) {
}
