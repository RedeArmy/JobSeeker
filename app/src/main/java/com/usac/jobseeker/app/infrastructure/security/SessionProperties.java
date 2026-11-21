package com.usac.jobseeker.app.infrastructure.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sesión web (ADR-0020): inactividad ≤ 30 min y límite absoluto ≤ 8 h, configurables. El prefijo
 * `__Host-` de la cookie exige HTTPS, así que solo el perfil prod lo usa.
 */
@ConfigurationProperties("jobseeker.auth.session")
public record SessionProperties(
    @DefaultValue("30m") Duration idleTimeout,
    @DefaultValue("8h") Duration absoluteTimeout,
    @DefaultValue("SESSION") String cookieName,
    @DefaultValue("false") boolean secureCookie) {}
