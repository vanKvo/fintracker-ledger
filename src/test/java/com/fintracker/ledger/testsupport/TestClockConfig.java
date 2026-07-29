package com.fintracker.ledger.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * Replaces the application's {@code Clock.systemUTC()} bean with a {@link MutableTestClock} so
 * REQ-5.1 period classification is deterministic. The default position — 2026-07-15T12:00Z — is
 * mid-month and mid-day so that neither a month boundary nor a day boundary is accidentally load
 * bearing; tests that care about a boundary move the clock there explicitly.
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant DEFAULT_NOW = Instant.parse("2026-07-15T12:00:00Z");

    /**
     * Deliberately not named {@code clock()}: that is the bean name {@code TimeConfig} already
     * uses, and Spring Boot refuses to override an existing definition by default. Registering a
     * second, {@code @Primary} Clock instead leaves the production bean in place and simply wins
     * injection — and the concrete return type lets tests autowire {@link MutableTestClock}
     * directly to move time.
     */
    @Bean
    @Primary
    public MutableTestClock testClock() {
        return new MutableTestClock(DEFAULT_NOW);
    }
}
