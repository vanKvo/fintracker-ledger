package com.fintracker.ledger.config;

import com.fintracker.ledger.shared.UserContextHolder;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Sets the PostgreSQL session variable {@code app.current_user_id} before every jOOQ query
 * and resets it after, so that RLS policies on all ledger tables can read it via
 * {@code current_setting('app.current_user_id', true)::uuid}.
 *
 * Spring Boot's jOOQ auto-configuration picks up any {@link ExecuteListenerProvider} beans
 * automatically, so no manual {@code DSLContext} wiring is needed.
 */
@Configuration
public class RlsExecuteListener extends DefaultExecuteListener {

    @Bean
    public ExecuteListenerProvider rlsExecuteListenerProvider() {
        return new DefaultExecuteListenerProvider(new RlsExecuteListener());
    }

    @Override
    public void start(ExecuteContext ctx) {
        UUID userId = UserContextHolder.get();
        if (userId == null || ctx.connection() == null) {
            return;
        }
        try (var stmt = ctx.connection().prepareStatement("SET app.current_user_id = ?")) {
            stmt.setString(1, userId.toString());
            stmt.execute();
        } catch (SQLException e) {
            throw new org.jooq.exception.DataAccessException("Failed to set RLS context", e);
        }
    }

    @Override
    public void end(ExecuteContext ctx) {
        if (ctx.connection() == null) {
            return;
        }
        // Reset so pooled connections never leak one user's identity to the next request.
        try (var stmt = ctx.connection().createStatement()) {
            stmt.execute("RESET app.current_user_id");
        } catch (SQLException ignored) {}
    }
}
