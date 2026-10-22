package com.darshan.recon.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// A fixed clock the test can move forward, so that every poll has its own receivedAt.
final class AdjustableClock extends Clock {

    private Instant now;

    AdjustableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the test clock is UTC only");
    }

    @Override
    public Instant instant() {
        return now;
    }
}
