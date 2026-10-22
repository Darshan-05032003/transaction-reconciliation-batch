package com.darshan.recon.ingest.file;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

// Fields never contain commas, so neither side needs quoting rules.
public final class StatementRecordFormat {

    public static final String HEADER_TAG = "HDR";
    public static final String DETAIL_TAG = "DTL";
    public static final String TRAILER_TAG = "TRL";
    public static final String SEPARATOR = ",";
    public static final int AMOUNT_SCALE = 2;

    private static final int HEADER_FIELDS = 4;
    private static final int DETAIL_FIELDS = 6;
    private static final int TRAILER_FIELDS = 3;
    private static final int DETAIL_AMOUNT_INDEX = 3;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern DECIMAL_AMOUNT = Pattern.compile("-?\\d+(\\.\\d{1,2})?");

    private StatementRecordFormat() {
    }

    public static String formatHeader(StatementHeader header) {
        return String.join(SEPARATOR, HEADER_TAG, header.bankCode(), header.accountNumber(),
                header.statementDate().toString());
    }

    public static String formatDetail(StatementDetail detail) {
        return String.join(SEPARATOR, DETAIL_TAG, detail.bankReference(), detail.valueDate().toString(),
                formatAmount(detail.amount()), detail.currency(), detail.description());
    }

    public static String formatTrailer(StatementTrailer trailer) {
        return String.join(SEPARATOR, TRAILER_TAG, Integer.toString(trailer.recordCount()),
                formatAmount(trailer.totalAmount()));
    }

    public static String formatAmount(BigDecimal amount) {
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN).toPlainString();
    }

    public static boolean isDetail(String line) {
        return line.startsWith(DETAIL_TAG + SEPARATOR);
    }

    public static StatementHeader parseHeader(String line) {
        String[] fields = split(line, HEADER_TAG, HEADER_FIELDS);
        return new StatementHeader(
                requireText(fields[1], "bankCode"),
                requireText(fields[2], "accountNumber"),
                parseDate(fields[3], "statementDate"));
    }

    public static StatementDetail parseDetail(String line) {
        String[] fields = split(line, DETAIL_TAG, DETAIL_FIELDS);
        return new StatementDetail(
                requireText(fields[1], "bankReference"),
                parseDate(fields[2], "valueDate"),
                parseAmount(fields[DETAIL_AMOUNT_INDEX]),
                parseCurrency(fields[4]),
                fields[5]);
    }

    public static StatementTrailer parseTrailer(String line) {
        String[] fields = split(line, TRAILER_TAG, TRAILER_FIELDS);
        int recordCount;
        try {
            recordCount = Integer.parseInt(fields[1].trim());
        } catch (NumberFormatException e) {
            throw new MalformedRecordException("recordCount is not an integer: " + fields[1]);
        }
        if (recordCount < 0) {
            throw new MalformedRecordException("recordCount must not be negative: " + recordCount);
        }
        return new StatementTrailer(recordCount, parseAmount(fields[2]));
    }

    // Control totals need the amount of a DTL line even when the rest of the line is defective.
    public static BigDecimal detailAmountOrNull(String line) {
        String[] fields = line.split(SEPARATOR, -1);
        if (fields.length <= DETAIL_AMOUNT_INDEX) {
            return null;
        }
        try {
            return parseAmount(fields[DETAIL_AMOUNT_INDEX]);
        } catch (MalformedRecordException e) {
            return null;
        }
    }

    public static BigDecimal parseAmount(String text) {
        String trimmed = text.trim();
        if (!DECIMAL_AMOUNT.matcher(trimmed).matches()) {
            throw new MalformedRecordException("amount is not a decimal with at most two places: " + text);
        }
        return new BigDecimal(trimmed).setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    private static String[] split(String line, String expectedTag, int expectedFields) {
        String[] fields = line.split(SEPARATOR, -1);
        if (!expectedTag.equals(fields[0])) {
            throw new MalformedRecordException("expected a " + expectedTag + " record but found: " + fields[0]);
        }
        if (fields.length != expectedFields) {
            throw new MalformedRecordException(
                    "expected " + expectedFields + " fields but found " + fields.length);
        }
        return fields;
    }

    private static String requireText(String value, String field) {
        if (value.isBlank()) {
            throw new MalformedRecordException(field + " is blank");
        }
        return value.trim();
    }

    private static LocalDate parseDate(String text, String field) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new MalformedRecordException(field + " is not an ISO date: " + text);
        }
    }

    private static String parseCurrency(String text) {
        String trimmed = text.trim();
        if (!CURRENCY.matcher(trimmed).matches()) {
            throw new MalformedRecordException("currency is not a three-letter ISO code: " + text);
        }
        return trimmed;
    }
}
