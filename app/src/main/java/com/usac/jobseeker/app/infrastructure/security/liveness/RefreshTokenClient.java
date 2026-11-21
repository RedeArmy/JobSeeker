package com.usac.jobseeker.app.infrastructure.security.liveness;

/** Lo que la prueba de vida necesita de Clavaris: renovar y revocar un refresh token. */
public interface RefreshTokenClient {

  /** Resultado de una renovación; solo {@code InvalidGrant} es definitivo (ADR-0020, regla 2). */
  sealed interface Result {
    /**
     * @param newRefreshToken el sucesor, o null si Clavaris no rota el token
     */
    record Renewed(String newRefreshToken) implements Result {}

    /** `invalid_grant`: el token fue revocado, caducó o se reutilizó. La sesión debe cerrarse. */
    record InvalidGrant() implements Result {}

    /** Red, tiempo agotado, 5xx o cualquier otra respuesta: no prueba que la cuenta esté fuera. */
    record Transient(String reason) implements Result {}
  }

  Result refresh(String refreshToken);

  /** Revoca el token en Clavaris, lo que además termina su sesión allí. Devuelve si lo logró. */
  boolean revoke(String refreshToken);
}
