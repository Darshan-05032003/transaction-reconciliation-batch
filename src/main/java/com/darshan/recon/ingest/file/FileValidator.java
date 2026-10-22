package com.darshan.recon.ingest.file;

import com.darshan.recon.ingest.file.FileValidation.Rejected;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Template Method: the streaming algorithm (first line is the header, last line is the
// trailer, everything in between feeds the control totals) is fixed here; how each record
// is parsed and what "the totals match" means are left to the subclass.
public abstract class FileValidator {

    public final FileValidation validate(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null) {
                return new Rejected("file is empty");
            }
            StatementHeader header;
            try {
                header = parseHeader(first);
            } catch (MalformedRecordException e) {
                return new Rejected("invalid header: " + e.getMessage());
            }

            ControlTotals totals = new ControlTotals();
            String candidateTrailer = null;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                // A line only counts once the next one proves it is not the trailer.
                if (candidateTrailer != null) {
                    accumulate(candidateTrailer, totals);
                }
                candidateTrailer = line;
            }
            if (candidateTrailer == null) {
                return new Rejected("file has no trailer");
            }
            StatementTrailer trailer;
            try {
                trailer = parseTrailer(candidateTrailer);
            } catch (MalformedRecordException e) {
                return new Rejected("invalid trailer: " + e.getMessage());
            }
            return validateTotals(header, trailer, totals);
        }
    }

    protected abstract StatementHeader parseHeader(String line);

    protected abstract StatementTrailer parseTrailer(String line);

    protected abstract FileValidation validateTotals(StatementHeader header, StatementTrailer trailer,
                                                     ControlTotals totals);

    // Lines between header and trailer that are not data records count neither towards the
    // record count nor the total; the load step reports them as skipped lines.
    protected void accumulate(String line, ControlTotals totals) {
        if (StatementRecordFormat.isDetail(line)) {
            totals.add(line);
        }
    }
}
