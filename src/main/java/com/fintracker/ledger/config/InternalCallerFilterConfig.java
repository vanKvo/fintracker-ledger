package com.fintracker.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Registers {@link InternalCallerFilter} against the explicit internal-route URL
 * prefixes — and only those.
 *
 * <p>Why a {@link FilterRegistrationBean} instead of annotating the filter with
 * {@code @Component}: a component-scanned filter is auto-registered for every URL
 * ({@code /*}), which is what forced the old design to re-derive "is this request
 * internal?" inside the filter from the RAW, undecoded {@code getRequestURI()} — the
 * bypass this split removes. Here the mapping lives in exactly one place, and the
 * servlet container matches these patterns against the DECODED request path, so an
 * encoded spelling of an internal route (e.g. {@code %69nternal}) still matches and is
 * gated. The filter bean is deliberately NOT exposed as a standalone bean, so there is
 * no double registration.
 *
 * <p>Servlet URL patterns cannot express the middle {@code **} of the logical route
 * shape {@code /api/v1/ledger/**&#47;internal/**}, so each internal controller's prefix is
 * listed explicitly — adding a new internal controller means adding its prefix here,
 * which keeps exposure of internal routes an explicit, reviewable act.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so the caller-identity check runs before
 * {@link UserContextFilter} on these routes: both a verified caller AND an internal user
 * id are required on every internal call.
 */
@Configuration
public class InternalCallerFilterConfig {

    @Bean
    public FilterRegistrationBean<InternalCallerFilter> internalCallerFilterRegistration(
            @Value("${ledger.internal.allowed-caller-arns:}") List<String> allowedCallerArns) {
        var registration = new FilterRegistrationBean<>(new InternalCallerFilter(allowedCallerArns));
        registration.addUrlPatterns(
                "/api/v1/ledger/transactions/internal/*",
                "/api/v1/ledger/statements/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
