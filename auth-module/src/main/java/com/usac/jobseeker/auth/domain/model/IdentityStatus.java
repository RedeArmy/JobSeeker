package com.usac.jobseeker.auth.domain.model;

/**
 * Estado de la identidad de una cuenta en Clavaris, tal como lo reflejan sus webhooks (ADR-0020,
 * superficie C). Es un espejo y no la fuente de verdad: un evento puede perderse.
 */
public enum IdentityStatus {
  ACTIVE,
  SUSPENDED,
  /** Terminal: una identidad borrada en Clavaris no vuelve. */
  DELETED
}
