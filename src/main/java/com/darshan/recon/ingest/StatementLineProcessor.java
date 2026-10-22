package com.darshan.recon.ingest;

import com.darshan.recon.ingest.file.MalformedRecordException;
import com.darshan.recon.ingest.file.StatementDetail;
import com.darshan.recon.ingest.file.StatementRecordFormat;
import org.springframework.batch.infrastructure.item.ItemProcessor;

public class StatementLineProcessor implements ItemProcessor<RawStatementLine, StatementLineRow> {

    static final int MAX_REFERENCE_LENGTH = 64;
    static final int MAX_DESCRIPTION_LENGTH = 255;

    private final long statementFileId;
    private final String accountNumber;

    public StatementLineProcessor(long statementFileId, String accountNumber) {
        this.statementFileId = statementFileId;
        this.accountNumber = accountNumber;
    }

    @Override
    public StatementLineRow process(RawStatementLine line) {
        StatementDetail detail = StatementRecordFormat.parseDetail(line.text());
        if (detail.bankReference().length() > MAX_REFERENCE_LENGTH) {
            throw new MalformedRecordException("bankReference is longer than " + MAX_REFERENCE_LENGTH + " characters");
        }
        String description = detail.description().trim();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new MalformedRecordException("description is longer than " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return new StatementLineRow(statementFileId, line.lineNumber(), detail.bankReference(), accountNumber,
                detail.amount(), detail.currency(), detail.valueDate(), description.isEmpty() ? null : description);
    }
}
