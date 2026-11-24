package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.common.domain.model.AccountId;
import jakarta.servlet.ServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * El ámbito en el que se puede recordar la cuenta ya resuelta: lo abre y lo cierra el `ActorFilter`
 * alrededor de una petición autenticada, nunca el azar de qué haya enlazado el hilo.
 *
 * <p>Que el ámbito sea explícito importa: antes bastaba con que existiera *alguna* petición
 * enlazada al hilo, y Spring Test mantiene una abierta durante todo un método de prueba, así que
 * una prueba que cambiaba la cuenta en la base de datos y volvía a preguntar veía la resolución
 * vieja. Ahora, fuera de una petición que pasó por el filtro (pruebas que llaman a un caso de uso,
 * el sondeador, los lotes) no hay ámbito y cada pregunta va a la base de datos.
 */
final class ActorResolutionScope {

  static final String ATTRIBUTE = ActorResolutionScope.class.getName() + ".RESOLVED";

  private ActorResolutionScope() {}

  /** Abre el ámbito de la petición; lo llama el filtro antes de resolver al actor. */
  static void open(ServletRequest request) {
    request.setAttribute(ATTRIBUTE, new ConcurrentHashMap<AccountId, Object>());
  }

  /** Lo cierra el filtro al terminar, aunque la petición falle. */
  static void close(ServletRequest request) {
    request.removeAttribute(ATTRIBUTE);
  }

  /** Lo recordado en el ámbito de la petición en curso, o vacío si no hay ámbito abierto. */
  @SuppressWarnings("unchecked")
  static Optional<Map<AccountId, Object>> current() {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        (Map<AccountId, Object>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
  }

  /** Olvida lo que se sabía de una cuenta: la base de datos cambió y lo recordado ya no vale. */
  static void forget(AccountId accountId) {
    current().ifPresent(scope -> scope.remove(accountId));
  }
}
