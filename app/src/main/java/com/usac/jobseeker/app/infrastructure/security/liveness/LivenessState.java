package com.usac.jobseeker.app.infrastructure.security.liveness;

import java.time.Instant;

/** Estado de la prueba de vida de una sesión, tal como está en Redis. */
public record LivenessState(
    String encryptedRefreshToken, Instant lastRenewed, Instant retryNotBefore) {}
