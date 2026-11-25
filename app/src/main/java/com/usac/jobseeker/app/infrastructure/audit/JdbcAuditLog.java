package com.usac.jobseeker.app.infrastructure.audit;

import com.usac.jobseeker.common.application.audit.AuditEntry;
import com.usac.jobseeker.common.application.audit.AuditPort;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escribe en `audit_log`, que es de `app` (ADR-0027). Corre dentro de la transacción de quien
 * audita, así que su fila y el cambio que registra se confirman o se descartan juntos. El
 * `trace_id` sale del contexto del log, para poder seguir la acción hasta su petición; `details` ya
 * viene restringido a valores simples por {@link AuditEntry} (BR-DATA-01).
 */
@Component
public class JdbcAuditLog implements AuditPort {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;

  public JdbcAuditLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(AuditEntry entry) {
    jdbc.sql(
            "insert into audit_log (id, actor_account_id, action, target_type, target_id, trace_id,"
                + " details) values (:id, :actor, :action, :targetType, :targetId, :traceId,"
                + " cast(:details as jsonb))")
        .param("id", UUID.randomUUID())
        .param("actor", entry.actor().value())
        .param("action", entry.action())
        .param("targetType", entry.targetType())
        .param("targetId", entry.targetId())
        .param("traceId", MDC.get("traceId"))
        .param("details", JSON.writeValueAsString(entry.details()))
        .update();
  }
}
