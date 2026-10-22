package com.darshan.recon.report;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Collectors;

// RFC 4180 style: a field is quoted only when it contains a comma, a quote or a line break,
// so numbers and dates stay bare and the files load cleanly into spreadsheets and awk alike.
public final class CsvFormat {

    private CsvFormat() {
    }

    public static String line(Object... fields) {
        return Arrays.stream(fields).map(CsvFormat::field).collect(Collectors.joining(","));
    }

    static String field(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof BigDecimal amount ? amount.toPlainString() : value.toString();
        boolean needsQuotes = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return needsQuotes ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
