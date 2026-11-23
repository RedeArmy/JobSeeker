package com.usac.jobseeker.app.infrastructure.security.webhook;

import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.AccountSessionsInvalidationRequested;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cierra todas las sesiones web de una cuenta (ADR-0020, superficie C). Vive en `app` y no en
 * `auth-module` porque las sesiones son infraestructura de la cadena web (Spring Session sobre
 * Redis), que `auth-module` no conoce.
 *
 * <p>Es idempotente: borrar sesiones que ya no existen no es un error, y repetir el evento no
 * produce más efecto. Si Redis falla lanza la excepción y el sondeador reintenta con retroceso, sin
 * haber retrasado ni perdido la aceptación del webhook.
 */
public class SessionInvalidationConsumer implements OutboxEventConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(SessionInvalidationConsumer.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final FindByIndexNameSessionRepository<? extends Session> sessions;

  public SessionInvalidationConsumer(FindByIndexNameSessionRepository<? extends Session> sessions) {
    this.sessions = sessions;
  }

  @Override
  public String name() {
    return "close-account-sessions";
  }

  @Override
  public boolean handles(String eventType) {
    return AccountSessionsInvalidationRequested.EVENT_TYPE.equals(eventType);
  }

  @Override
  public void consume(OutboxEvent event) {
    String accountId = JSON.readTree(event.payload()).path("accountId").asString();
    if (accountId.isBlank()) {
      // Una carga sin cuenta nunca mejorará con reintentos: se deja constancia y se da por hecha.
      LOG.error("event=session_invalidation_invalid Evento de cierre de sesiones sin cuenta");
      return;
    }
    // El índice por principal es el `accountId` (DistributedSessionConfiguration).
    Set<String> ids = findSessionIds(accountId);
    ids.forEach(sessions::deleteById);
    LOG.info(
        "event=sessions_closed sessions={} Sesiones de la cuenta cerradas por webhook de Clavaris",
        ids.size());
  }

  private Set<String> findSessionIds(String accountId) {
    Map<String, ? extends Session> found = sessions.findByPrincipalName(accountId);
    return Set.copyOf(found.keySet());
  }
}
