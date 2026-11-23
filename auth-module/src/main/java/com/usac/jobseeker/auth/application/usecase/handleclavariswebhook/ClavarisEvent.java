package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import java.util.Arrays;
import java.util.Optional;

/**
 * Los eventos de Clavaris que tienen efecto en JobSeeker. Son los cuatro verificados contra
 * entregas reales del spike; el resto de los tipos del catálogo se acepta y se ignora: otro código
 * de estado haría que Clavaris reintentara y acabara desactivando el endpoint entero.
 *
 * <p>`account.banned` y `account.unbanned` parecen análogos a suspender y reactivar, pero su carga
 * no se ha comprobado con una entrega real: no se tratan hasta verificarlo.
 */
public enum ClavarisEvent {
  SUSPENDED("account.suspended", IdentityStatus.SUSPENDED, true),
  REACTIVATED("account.reactivated", IdentityStatus.ACTIVE, false),
  DELETED("account.deleted", IdentityStatus.DELETED, true),
  /**
   * No cambia el estado de la identidad, pero un token robado y reutilizado exige cerrar sesiones.
   */
  TOKEN_REUSE_DETECTED("refresh_token.reuse_detected", null, true);

  private final String wireType;
  private final IdentityStatus mirroredStatus;
  private final boolean closeSessions;

  ClavarisEvent(String type, IdentityStatus identityStatus, boolean closesSessions) {
    this.wireType = type;
    this.mirroredStatus = identityStatus;
    this.closeSessions = closesSessions;
  }

  public String type() {
    return wireType;
  }

  /** El estado de identidad que fija este evento, si fija alguno. */
  public Optional<IdentityStatus> identityStatus() {
    return Optional.ofNullable(mirroredStatus);
  }

  /** Si hay que cerrar todas las sesiones de la cuenta. */
  public boolean closesSessions() {
    return closeSessions;
  }

  /** El evento de este tipo de Clavaris, o vacío si no tiene efecto en JobSeeker. */
  public static Optional<ClavarisEvent> from(String eventType) {
    return Arrays.stream(values()).filter(event -> event.wireType.equals(eventType)).findFirst();
  }
}
