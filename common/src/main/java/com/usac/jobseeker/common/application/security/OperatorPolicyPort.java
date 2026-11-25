package com.usac.jobseeker.common.application.security;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * Puerto de salida: ¿esta cuenta es de un operador de la plataforma? Un operador hace acciones que
 * ninguna capacidad de usuario puede hacer, como verificar a un reclutador (BR-ACCT-11). No es una
 * capacidad más de la cuenta (BR-ACCT-01): es una lista de confianza que decide la configuración,
 * vacía por defecto, de modo que sin configurarla nadie lo es.
 */
public interface OperatorPolicyPort {

  boolean isOperator(AccountId accountId);
}
