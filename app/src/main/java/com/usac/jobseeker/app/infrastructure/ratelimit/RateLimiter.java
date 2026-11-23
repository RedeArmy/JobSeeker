package com.usac.jobseeker.app.infrastructure.ratelimit;

import com.usac.jobseeker.app.infrastructure.ratelimit.RateLimitProperties.Policy;

/**
 * Puerto del límite de tasa. Si el almacén no responde, la implementación **deja pasar** la
 * petición: un Redis caído no debe tumbar toda la API (la API Bearer no necesita Redis para nada
 * más), y los demás controles (firma, token, cuenta) siguen en pie. Es la decisión inversa a la de
 * un control de acceso, y queda registrada y medida.
 */
interface RateLimiter {

  /** Consume una ficha del cubo de `key` bajo `policyName`. */
  RateLimitDecision tryConsume(String policyName, Policy policy, String key);
}
