package com.usac.jobseeker.common.domain.model;

/**
 * Capacidades de una cuenta (BR-ACCT-01, ADR-0008): independientes y no excluyentes. Son un flag de
 * negocio propio de JobSeeker, no un concepto de Clavaris.
 */
public enum Capability {
  CANDIDATE,
  RECRUITER
}
