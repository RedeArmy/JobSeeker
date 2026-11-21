package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.common.domain.model.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * El `Actor` de la petición en curso, reconstruido por {@link ActorFilter}. Es la única forma
 * sancionada de saber quién actúa: nunca se leen capacidades del token (ADR-0020).
 */
public final class CurrentActor {

  static final String ATTRIBUTE = CurrentActor.class.getName() + ".ACTOR";

  private CurrentActor() {}

  public static Optional<Actor> from(HttpServletRequest request) {
    return Optional.ofNullable((Actor) request.getAttribute(ATTRIBUTE));
  }
}
