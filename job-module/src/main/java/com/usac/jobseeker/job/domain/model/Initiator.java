package com.usac.jobseeker.job.domain.model;

/** Quién provoca una transición: el dueño a mano, o el sistema (el archivado automático). */
public enum Initiator {
  OWNER,
  SYSTEM
}
