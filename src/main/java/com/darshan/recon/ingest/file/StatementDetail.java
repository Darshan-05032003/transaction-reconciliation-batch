package com.darshan.recon.ingest.file;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StatementDetail(
        String bankReference,
        LocalDate valueDate,
        BigDecimal amount,
        String currency,
        String description) {
}
