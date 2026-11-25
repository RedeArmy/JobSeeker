package com.usac.jobseeker.common.application.audit;

/**
 * Puerto de salida del registro de auditoría. Lo implementa `app`, dueña de `audit_log` (ADR-0027).
 * Se escribe en **la misma transacción** de la acción que audita: o quedan las dos, o ninguna, de
 * modo que no hay un cambio sin rastro ni un rastro de un cambio que no ocurrió.
 */
public interface AuditPort {

  void append(AuditEntry entry);
}
