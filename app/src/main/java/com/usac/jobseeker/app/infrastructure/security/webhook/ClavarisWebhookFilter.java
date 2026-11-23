package com.usac.jobseeker.app.infrastructure.security.webhook;

import com.usac.jobseeker.app.infrastructure.security.ErrorEnvelope;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookCommand;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookResult;
import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Receptor de webhooks de Clavaris (ADR-0020, superficie C). Es el único responsable de la
 * petición: la autenticidad la da **solo** la firma HMAC sobre el cuerpo crudo, sin sesión ni
 * Bearer. Responde 2xx únicamente tras confirmar la transacción del caso de uso; ante cualquier
 * fallo propio responde 5xx para que Clavaris reintente, porque un evento perdido es una sesión que
 * debía cerrarse y sigue abierta.
 *
 * <p>Nunca se registra el cuerpo (trae correo, nombre y teléfono; BR-DATA-01) ni el secreto.
 */
public class ClavarisWebhookFilter extends OncePerRequestFilter {

  static final String SIGNATURE = "Clavaris-Signature";
  static final String EVENT_TYPE = "Clavaris-Event-Type";
  static final String EVENT_ID = "Clavaris-Event-Id";
  static final String TRACE_ID = "Clavaris-Trace-Id";
  static final String METRIC = "jobseeker_webhook_total";

  private static final Logger LOG = LoggerFactory.getLogger(ClavarisWebhookFilter.class);
  private static final String ERROR_CODE_SIGNATURE = "WEBHOOK_SIGNATURE_INVALID";

  private final HandleClavarisWebhookUseCase useCase;
  private final WebhookProperties properties;
  private final WebhookSignatureVerifier verifier;
  private final MeterRegistry meters;
  private final Clock clock;

  public ClavarisWebhookFilter(
      HandleClavarisWebhookUseCase useCase,
      WebhookProperties properties,
      MeterRegistry meters,
      Clock clock) {
    this.useCase = useCase;
    this.properties = properties;
    this.verifier = new WebhookSignatureVerifier(properties.secrets(), properties.tolerance());
    this.meters = meters;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException {
    if (!"POST".equals(request.getMethod())) {
      response.setHeader("Allow", "POST");
      reply(request, response, 405, "METHOD_NOT_ALLOWED", "Método no permitido.", "method");
      return;
    }
    if (!properties.configured()) {
      // Cerrado por defecto: sin secreto no se puede autenticar nada, y aceptar sin firma sería
      // dejar que cualquiera cierre las sesiones de otro.
      LOG.error("event=webhook_not_configured Receptor de webhooks sin secreto configurado");
      reply(request, response, 503, "WEBHOOK_NOT_CONFIGURED", "Receptor no disponible.", "closed");
      return;
    }
    if (request.getContentLengthLong() > properties.maxBodyBytes()) {
      reply(request, response, 413, "PAYLOAD_TOO_LARGE", "Cuerpo demasiado grande.", "too_large");
      return;
    }
    Optional<byte[]> body = readBounded(request);
    if (body.isEmpty()) {
      reply(request, response, 413, "PAYLOAD_TOO_LARGE", "Cuerpo demasiado grande.", "too_large");
      return;
    }
    var outcome = verifier.verify(request.getHeader(SIGNATURE), body.get(), clock.instant());
    if (outcome != WebhookSignatureVerifier.Outcome.VALID) {
      String reason = outcome.name().toLowerCase(java.util.Locale.ROOT);
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "event=webhook_rejected reason={} Webhook de Clavaris rechazado por su firma", reason);
      }
      reply(
          request, response, 401, ERROR_CODE_SIGNATURE, "Firma no válida.", "signature_" + reason);
      return;
    }
    Optional<HandleClavarisWebhookCommand> command =
        ClavarisWebhookParser.parse(
            body.get(),
            request.getHeader(EVENT_TYPE),
            request.getHeader(EVENT_ID),
            request.getHeader(TRACE_ID));
    if (command.isEmpty()) {
      // Firmado pero ilegible: un 4xx hace que Clavaris deje de insistir en algo que no mejorará.
      reply(request, response, 400, "WEBHOOK_PAYLOAD_INVALID", "Cuerpo no válido.", "invalid");
      return;
    }
    apply(request, response, command.get());
  }

  // Cualquier fallo al aplicar es un 503: Clavaris reintenta y no se pierde el evento.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void apply(
      HttpServletRequest request,
      HttpServletResponse response,
      HandleClavarisWebhookCommand command)
      throws IOException {
    HandleClavarisWebhookResult result;
    try {
      result = useCase.execute(command);
    } catch (RuntimeException e) {
      LOG.error(
          "event=webhook_failed eventType={} Fallo al aplicar el webhook; Clavaris reintentará",
          command.eventType(),
          e);
      reply(request, response, 503, "WEBHOOK_UNAVAILABLE", "No se pudo procesar.", "error");
      return;
    }
    LOG.info(
        "event=webhook_processed eventType={} result={} Webhook de Clavaris procesado",
        command.eventType(),
        result);
    meters.counter(METRIC, "outcome", result.name().toLowerCase(java.util.Locale.ROOT)).increment();
    response.setStatus(204);
  }

  /** Lee como mucho `maxBodyBytes`; vacío si el cuerpo es mayor (p. ej. sin `Content-Length`). */
  private Optional<byte[]> readBounded(HttpServletRequest request) throws IOException {
    int max = properties.maxBodyBytes();
    try (ServletInputStream in = request.getInputStream()) {
      byte[] data = in.readNBytes(max + 1);
      return data.length > max ? Optional.empty() : Optional.of(data);
    }
  }

  private void reply(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message,
      String outcome)
      throws IOException {
    meters.counter(METRIC, "outcome", outcome).increment();
    ErrorEnvelope.write(request, response, status, code, message);
  }
}
