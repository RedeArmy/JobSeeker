package com.usac.jobseeker.app.infrastructure.web;

import com.usac.jobseeker.app.infrastructure.security.ErrorEnvelope;
import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.PublishingDeniedException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.SuggestionsDeniedException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

  /**
   * El par `(origen, destino)` no existe o no lo puede hacer quien lo pide: 409 con su propio
   * código, porque volver a leer y reintentar no lo arregla (a diferencia de `STALE_STATE`).
   */
  @ExceptionHandler(InvalidTransitionException.class)
  ResponseEntity<Object> invalidTransition(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ErrorEnvelope.body(
                request,
                "INVALID_TRANSITION",
                "Ese cambio de estado no es válido desde el estado actual."));
  }

  /** La operación no aplica al estado actual del recurso: 409 con su propio código. */
  @ExceptionHandler(InvalidStateException.class)
  ResponseEntity<Object> invalidState(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ErrorEnvelope.body(
                request, "INVALID_STATE", "La operación no aplica al estado actual del recurso."));
  }

  /**
   * La cuenta reclutadora no puede publicar más por la confianza que se le tiene (REQ-R-10). Cada
   * motivo tiene su código: el cliente puede ofrecer verificarse, o liberar una vacante, y no son
   * lo mismo. `QUOTA_EXCEEDED` es 429 como el resto de topes de uso (LLD §438).
   */
  @ExceptionHandler(PublishingDeniedException.class)
  ResponseEntity<Object> publishingDenied(
      PublishingDeniedException denied, HttpServletRequest request) {
    return switch (denied.reason()) {
      case NOT_VERIFIED ->
          ResponseEntity.status(HttpStatus.FORBIDDEN)
              .body(
                  ErrorEnvelope.body(
                      request,
                      "RECRUITER_NOT_VERIFIED",
                      "Verifica tu cuenta de reclutador para publicar más vacantes."));
      case SUSPENDED ->
          ResponseEntity.status(HttpStatus.FORBIDDEN)
              .body(
                  ErrorEnvelope.body(
                      request,
                      "RECRUITER_SUSPENDED",
                      "Tu cuenta de reclutador está suspendida y no puede publicar."));
      case QUOTA_EXCEEDED ->
          ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
              .body(
                  ErrorEnvelope.body(
                      request,
                      "QUOTA_EXCEEDED",
                      "Alcanzaste el máximo de vacantes publicadas; cierra alguna para publicar otra."));
    };
  }

  /**
   * La cuenta reclutadora no puede ver sugeridos (REQ-R-10, REQ-R-11): sin verificar o suspendida
   * es 403, y haber agotado la cuota de perfiles únicos de la ventana es 429, como el resto de
   * topes de uso. Los códigos son los mismos que los de publicar: la causa es la misma confianza.
   */
  @ExceptionHandler(SuggestionsDeniedException.class)
  ResponseEntity<Object> suggestionsDenied(
      SuggestionsDeniedException denied, HttpServletRequest request) {
    return switch (denied.reason()) {
      case NOT_VERIFIED ->
          ResponseEntity.status(HttpStatus.FORBIDDEN)
              .body(
                  ErrorEnvelope.body(
                      request,
                      "RECRUITER_NOT_VERIFIED",
                      "Tu cuenta de reclutador debe estar verificada para ver candidatos sugeridos."));
      case SUSPENDED ->
          ResponseEntity.status(HttpStatus.FORBIDDEN)
              .body(
                  ErrorEnvelope.body(
                      request,
                      "RECRUITER_SUSPENDED",
                      "Tu cuenta de reclutador está suspendida y no puede ver candidatos."));
      case QUOTA_EXCEEDED ->
          ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
              .body(
                  ErrorEnvelope.body(
                      request,
                      "QUOTA_EXCEEDED",
                      "Alcanzaste el máximo de perfiles que puedes ver en 24 horas; vuelve más tarde."));
    };
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

  /**
   * Un archivo subido no se acepta (ADR-0021): 422 con el motivo como detalle, con la misma forma
   * que una validación, para que la interfaz diga qué corregir. Nunca devuelve el nombre del
   * archivo.
   */
  @ExceptionHandler(FileRejectedException.class)
  ResponseEntity<Object> fileRejected(FileRejectedException exception, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
        .body(
            ErrorEnvelope.body(
                request,
                "FILE_REJECTED",
                "El archivo no se aceptó.",
                List.of(Map.of("field", "file", "code", exception.reason().name()))));
  }

  /**
   * Un archivo mayor que el límite de `multipart` se corta antes de leerlo: es el mismo rechazo por
   * tamaño que el del caso de uso, con el mismo contrato, no un 413 genérico.
   */
  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return fileRejected(
        new FileRejectedException(FileRejectedException.Reason.TOO_LARGE),
        ((ServletWebRequest) request).getRequest());
  }

  /**
   * Un servicio del que depende la operación no responde (almacén, antivirus): 503 y el resto del
   * sistema sigue (`02-hld-system-design.md` §9). Se registra qué servicio falló, nunca la
   * petición.
   */
  @ExceptionHandler(UpstreamUnavailableException.class)
  ResponseEntity<Object> upstreamUnavailable(
      UpstreamUnavailableException exception, HttpServletRequest request) {
    LOG.warn(
        "event=upstream_unavailable dependency={} Un servicio externo no responde",
        exception.dependency(),
        exception.getCause());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            ErrorEnvelope.body(
                request,
                "UPSTREAM_UNAVAILABLE",
                "Un servicio necesario no está disponible. Inténtalo más tarde."));
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
