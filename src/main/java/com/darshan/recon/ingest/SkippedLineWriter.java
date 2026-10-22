package com.darshan.recon.ingest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

// Appends one CSV row per skipped line. Each row is flushed immediately, so the file is
// complete even if the job dies later; the number of rows is bounded by the skip limit.
public class SkippedLineWriter implements SkipListener<RawStatementLine, StatementLineRow> {

    static final String HEADER = "line_number,reason,raw_line";

    private final Path errorFile;

    public SkippedLineWriter(Path errorFile) {
        this.errorFile = errorFile;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof FlatFileParseException parseException) {
            append(parseException.getLineNumber(), t.getMessage(), parseException.getInput());
        } else {
            append(0, t.getMessage(), "");
        }
    }

    @Override
    public void onSkipInProcess(RawStatementLine line, Throwable t) {
        append(line.lineNumber(), t.getMessage(), line.text());
    }

    private void append(int lineNumber, String reason, String rawLine) {
        try {
            boolean newFile = Files.notExists(errorFile);
            if (newFile) {
                Files.createDirectories(errorFile.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(errorFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) {
                    writer.write(HEADER);
                    writer.newLine();
                }
                writer.write(lineNumber + "," + quote(reason) + "," + quote(rawLine));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not record skipped line in " + errorFile, e);
        }
    }

    private static String quote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
