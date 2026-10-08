package com.fintracker.ledger.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * DP-LEDGER-CATEGORIES-02: the Data Pipeline refreshes its SYSTEM category copy every 5 minutes
 * with If-None-Match; the ETag is a hash of the response body, so an unchanged list costs a 304.
 */
@Configuration
public class InternalCategoryEtagFilterConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> internalCategoryEtagFilter() {
        var registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/ledger/categories/internal/system");
        // After InternalCallerFilter (HIGHEST_PRECEDENCE): unauthorized callers never get an ETag.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
