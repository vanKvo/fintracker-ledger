package com.fintracker.ledger.shared;

import java.util.UUID;

/**
 * Carries the authenticated user identity across thread boundaries within a single request.
 * Set by {@link com.fintracker.ledger.config.UserContextFilter} at request entry and read by
 * {@link com.fintracker.ledger.config.RlsExecuteListener} before each jOOQ query to populate
 * the {@code app.current_user_id} Postgres session variable used by RLS policies.
 *
 * Virtual-thread safe: each virtual thread has its own stack, so ThreadLocal values set on a
 * virtual thread are not shared with other virtual threads handling concurrent requests.
 */
public final class UserContextHolder {

    private static final ThreadLocal<UUID> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {}

    public static void set(UUID userId) {
        HOLDER.set(userId);
    }

    public static UUID get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
