package com.darshan.recon.ingest;

public final class IngestJobKeys {

    public static final String JOB_NAME = "ingestStatementJob";

    public static final String CHECKSUM_STEP = "computeChecksum";
    public static final String VALIDATE_STEP = "validateControlTotals";
    public static final String LOAD_STEP = "loadStatementLines";

    public static final String FILE_PATH_PARAMETER = "filePath";
    public static final String RECEIVED_AT_PARAMETER = "receivedAt";

    public static final String STATEMENT_FILE_ID = "statementFileId";
    public static final String ACCOUNT_NUMBER = "accountNumber";
    public static final String DECLARED_COUNT = "declaredCount";

    public static final String EXIT_DUPLICATE = "DUPLICATE";
    public static final String EXIT_REJECTED = "REJECTED";

    private IngestJobKeys() {
    }
}
