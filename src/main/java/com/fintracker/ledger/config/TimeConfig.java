package com.fintracker.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * REQ-5.1 requires budget period classification (past / current / future) and the month-end
 * closure cutoff to be derived from an injected {@link Clock} rather than {@code LocalDate.now()},
 * so that the behavior is deterministic under test and independent of the server's default zone.
 *
 * <p>UTC is the system-wide reference: budget months are calendar values with no zone of their
 * own, and pinning them to UTC keeps a month boundary from shifting with deployment region.
 * Tests override this bean with {@code Clock.fixed(...)}.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
