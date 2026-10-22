package com.darshan.recon.matching;

public final class ReconcileJobKeys {

    public static final String JOB_NAME = "reconcileJob";

    public static final String CREATE_RUN_STEP = "createReconRun";
    public static final String MATCH_STEP = "matchCandidates";
    public static final String PARTITION_STEP = "matchPartition";
    public static final String FINALISE_STEP = "finaliseReconRun";

    public static final String STATEMENT_FILE_ID_PARAMETER = "statementFileId";
    public static final String GRID_SIZE_PARAMETER = "gridSize";
    public static final String RERUN_PARAMETER = "rerun";

    public static final String RECON_RUN_ID = "reconRunId";
    public static final String ACCOUNT_NUMBER = "accountNumber";
    public static final String WINDOW_FROM = "windowFrom";
    public static final String WINDOW_TO = "windowTo";

    public static final String PARTITION_INDEX = "partitionIndex";
    public static final String PARTITION_COUNT = "partitionCount";

    private ReconcileJobKeys() {
    }
}
