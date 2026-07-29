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
        // Postgres's SET command does not accept bind parameters ("SET x = $1" is a syntax error —
        // SET is a utility statement, not a parameterizable query). set_config(...) is a regular
        // function call and does accept them; is_local=false matches SET's session-wide scope
        // (paired with the plain RESET below, not transaction-scoped SET LOCAL semantics).
        //
        // Deliberately NOT closed here (no try-with-resources). ctx.connection() is the same
        // connection jOOQ is about to reuse for the actual query. Outside an active Spring
        // transaction, Spring's TransactionAwareDataSourceProxy treats closing any resource derived
        // from that connection as "done with it" and immediately returns the physical connection to
        // the HikariCP pool — leaving jOOQ's next statement on the same connection object failing
        // with "Connection is closed". The statement/result set are cleaned up when the connection
        // itself is eventually closed at the end of jOOQ's own lifecycle.
        try {
            var stmt = ctx.connection().prepareStatement("SELECT set_config('app.current_user_id', ?, false)");
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
        // Reset so pooled connections never leak one user's identity to the next request. Same
        // "don't close what you didn't open" reasoning as start() above — this runs at the true end
        // of the query lifecycle, but closing it here would still prematurely release the connection
        // jOOQ itself is about to return to the pool through its own path.
        try {
            ctx.connection().createStatement().execute("RESET app.current_user_id");
        } catch (SQLException ignored) {}
    }
}
