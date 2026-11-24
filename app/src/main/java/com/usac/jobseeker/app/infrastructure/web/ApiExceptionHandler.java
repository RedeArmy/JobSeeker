package com.usac.jobseeker.app.infrastructure.web;

import com.usac.jobseeker.app.infrastructure.security.ErrorEnvelope;
import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Todo error que sale de un controlador llega al cliente con el sobre estándar
 * (`api-contract-overview.md` §6), con el `traceId` y sin detalles internos (BR-DATA-01). Sin esto
 * Spring respondería con su `ProblemDetail`, que es otro formato, o con una traza.
 *
 * <p>Los errores de la capa de seguridad (401/403/429) no pasan por aquí: los escriben sus propios
 * filtros con {@link ErrorEnvelope}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Errores de MVC y `ResponseStatusException`: conservan su estado, con el sobre estándar. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    HttpServletRequest servlet = ((ServletWebRequest) request).getRequest();
    return ResponseEntity.status(status)
        .headers(headers)
        .body(ErrorEnvelope.body(servlet, code(status), message(status)));
  }

  /**
   * Una regla del dominio no se cumple: 400 con el detalle por campo (`api-contract-overview.md`
   * §6). Los módulos lanzan la excepción sin saber de HTTP.
   */
  @ExceptionHandler(ValidationFailedException.class)
  ResponseEntity<Object> validationFailed(
      ValidationFailedException exception, HttpServletRequest request) {
    List<Map<String, String>> details =
        exception.violations().stream()
            .map(v -> Map.of("field", v.field(), "code", v.code()))
            .toList();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ErrorEnvelope.body(
                request, "VALIDATION_FAILED", "Hay datos que no son válidos.", details));
  }

  /**
   * Otra edición se guardó antes (bloqueo optimista): 409. Se cuenta el conflicto sin datos del
   * recurso; el cliente vuelve a leer y reintenta.
   */
  @ExceptionHandler(StaleStateException.class)
  ResponseEntity<Object> staleState(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ErrorEnvelope.body(
                request,
                "STALE_STATE",
                "El recurso cambió mientras lo editabas; vuelve a cargarlo."));
  }

  /** Lo que se quiso crear ya existe: 409 con su propio código, distinto de una versión vieja. */
  @ExceptionHandler(AlreadyExistsException.class)
  ResponseEntity<Object> alreadyExists(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorEnvelope.body(request, "ALREADY_EXISTS", "Eso ya existe."));
  }

  /**
   * La cuenta no tiene la capacidad que la operación exige: 403 con un código propio, porque el
   * cliente puede reaccionar distinto a «no tienes permiso» que a una sesión inválida (BR-ACCT-04).
   */
  @ExceptionHandler(ForbiddenCapabilityException.class)
  ResponseEntity<Object> forbiddenCapability(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            ErrorEnvelope.body(
                request,
                "FORBIDDEN_CAPABILITY",
                "Tu cuenta no tiene habilitada la capacidad necesaria para esta operación."));
  }

  /**
   * El recurso no existe para quien lo pide, o no es suyo: 404 en ambos casos, para no confirmar
   * que un identificador ajeno es válido (BR-ACCT-04).
   */
  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<Object> notFound(HttpServletRequest request) {
    HttpStatus status = HttpStatus.NOT_FOUND;
    return ResponseEntity.status(status)
        .body(ErrorEnvelope.body(request, code(status), message(status)));
  }

  /** Lo que nadie previó: 500 sin detalles; la causa queda en el registro con el `traceId`. */
  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
    LOG.error("event=unhandled_error Error no controlado en la API", exception);
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status)
        .body(ErrorEnvelope.body(request, code(status), message(status)));
  }

  /**
   * Códigos genéricos para errores que ningún dominio nombró; los propios del dominio
   * (`JOB_POSTING_NOT_FOUND`…) los pondrá cada módulo cuando los tenga.
   */
  static String code(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "REQUEST_INVALID";
      case 404 -> "RESOURCE_NOT_FOUND";
      case 405 -> "METHOD_NOT_ALLOWED";
      case 406, 415 -> "UNSUPPORTED_MEDIA_TYPE";
      case 500 -> "INTERNAL_ERROR";
      default -> status.is4xxClientError() ? "REQUEST_REJECTED" : "INTERNAL_ERROR";
    };
  }

  static String message(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "La petición no es válida.";
      case 404 -> "El recurso no existe.";
      case 405 -> "Método no permitido.";
      case 406, 415 -> "Tipo de contenido no admitido.";
      default -> status.is4xxClientError() ? "La petición fue rechazada." : "Error interno.";
    };
  }
}
