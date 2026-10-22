package com.darshan.recon.common.batch;

// Parameters shared by every way of launching a job (API, inbox poller).
public final class JobParameterKeys {

    // Non-identifying: who or what asked for the launch, kept on the execution for audit.
    public static final String LAUNCHED_BY = "launchedBy";

    private JobParameterKeys() {
    }
}
