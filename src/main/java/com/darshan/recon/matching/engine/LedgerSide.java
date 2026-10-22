package com.darshan.recon.matching.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LedgerSide(long id, String reference, BigDecimal amount, LocalDate valueDate, String description) {
}
