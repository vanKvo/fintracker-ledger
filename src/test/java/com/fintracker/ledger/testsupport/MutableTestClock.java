package com.fintracker.ledger.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} whose "now" can be moved from inside a test.
 *
 * <p>REQ-5.1 classifies a budget period as past / current / future, and the month-end closure job
 * keys off the same notion of "now". Both must therefore be driven by an injected clock rather
 * than {@code LocalDate.now()} — otherwise the suite's outcome depends on the wall-clock date it
 * happens to run on, and month-boundary behavior is untestable without waiting for a month
 * boundary. Tests that need a specific position within the month call {@link #setTo}.
 *
 * <p>Fixed to UTC deliberately: budget months are calendar values with no zone of their own, and
 * pinning them here keeps a month boundary from shifting with the machine's default zone.
 */
public final class MutableTestClock extends Clock {

    private volatile Instant instant;

    public MutableTestClock(Instant initial) {
        this.instant = initial;
    }

    /** Moves the clock to an exact instant. */
    public void setTo(Instant newInstant) {
        this.instant = newInstant;
    }

    /** Moves the clock to midday on the given date — far from any day boundary. */
    public void setTo(LocalDate date) {
        this.instant = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    /** The first day of the month this clock is currently in. */
    public LocalDate currentMonth() {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).withDayOfMonth(1);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
