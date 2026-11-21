package com.usac.jobseeker.auth.domain.model;

/** Ciclo de vida de eliminación de una cuenta (BR-DATA-04/05, ADR-0013). */
public enum DeletionStatus {
  ACTIVE,
  /** Periodo de gracia: volver a iniciar sesión cancelaría la solicitud (BR-DATA-04). */
  PENDING_DELETION,
  /** Estado terminal: la PII ya se anonimizó (BR-DATA-05). */
  ANONYMIZED
}
