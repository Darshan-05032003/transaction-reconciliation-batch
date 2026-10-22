package com.darshan.recon.ingest.file;

import com.darshan.recon.ingest.file.FileValidation.Accepted;
import com.darshan.recon.ingest.file.FileValidation.Rejected;
import org.springframework.stereotype.Component;

@Component
public class StatementFileValidator extends FileValidator {

    @Override
    protected StatementHeader parseHeader(String line) {
        return StatementRecordFormat.parseHeader(line);
    }

    @Override
    protected StatementTrailer parseTrailer(String line) {
        return StatementRecordFormat.parseTrailer(line);
    }

    @Override
    protected FileValidation validateTotals(StatementHeader header, StatementTrailer trailer, ControlTotals totals) {
        if (totals.matches(trailer)) {
            return new Accepted(header, trailer);
        }
        return new Rejected("trailer declares %d records totalling %s but the file contains %d records totalling %s"
                .formatted(trailer.recordCount(), StatementRecordFormat.formatAmount(trailer.totalAmount()),
                        totals.recordCount(), StatementRecordFormat.formatAmount(totals.totalAmount())));
    }
}
