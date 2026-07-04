package com.atlas.platform.identity.adapter

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Security wiring for the platform. Two details are load-bearing:
 *
 *   1. @EnableMethodSecurity activates the @PreAuthorize checks on controllers.
 *      Method security is opt-in; without this annotation every @PreAuthorize is
 *      silently ignored and the scope gates fail open — the worst failure mode.
 *
 *   2. The `hasAuthority('SCOPE_ledger:write')` checks require the authority to
 *      exist. The realm grants ledger scopes as OAuth client scopes, so tokens
 *      carry them in the standard `scope` claim and Boot's default converter
 *      yields SCOPE_ledger:write with no custom converter code.
 *
 * The chain is stateless (pure bearer-token API), CSRF is disabled accordingly,
 * and only liveness/metrics endpoints are anonymous — in production those sit
 * behind network policy, not public ingress (see infra/k8s).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { it.jwt { } }
        .build()
}
