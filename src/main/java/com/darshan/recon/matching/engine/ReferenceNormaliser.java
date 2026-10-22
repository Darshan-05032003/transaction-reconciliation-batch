package com.darshan.recon.matching.engine;

import java.util.Locale;
import java.util.regex.Pattern;

// Banks and ledgers disagree about case and padding of the same reference far more often
// than about the reference itself, so both sides are compared on this normalised form.
public final class ReferenceNormaliser {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ReferenceNormaliser() {
    }

    public static String normalise(String reference) {
        return WHITESPACE.matcher(reference).replaceAll(" ").trim().toUpperCase(Locale.ROOT);
    }

    // The pairing query joins on this expression; it must stay equivalent to normalise().
    public static String sql(String column) {
        return "upper(btrim(regexp_replace(" + column + ", '\\s+', ' ', 'g')))";
    }
}
