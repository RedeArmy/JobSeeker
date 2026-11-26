package com.usac.jobseeker.app.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Todo error de un controlador sale con el sobre estándar y sin detalles internos. */
class ApiExceptionHandlerTest {

  /** El atributo en el que `TraceIdFilter` deja el identificador (privado a su paquete). */
  private static final String TRACE_ATTRIBUTE =
      "com.usac.jobseeker.app.infrastructure.security.TraceIdFilter.TRACE_ID";

  @RestController
  static class Probe {
    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("detalle interno: clave=secreta");
    }

    @GetMapping("/missing")
    String missing() {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/conflict")
    String conflict() {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    @GetMapping("/invalid")
    String invalid() {
      throw new ValidationFailedException(
          List.of(
              new FieldViolation("fullName", "REQUIRED"), new FieldViolation("bio", "TOO_LONG")));
    }

    @GetMapping("/duplicate")
    String duplicate() {
      throw new AlreadyExistsException();
    }

    @GetMapping("/forbidden")
    String forbidden() {
      throw new ForbiddenCapabilityException();
    }

    @GetMapping("/absent")
    String absent() {
      throw new NotFoundException();
    }

    @GetMapping("/stale")
    String stale() {
      throw new StaleStateException();
    }

    @GetMapping("/rejected-file")
    String rejectedFile() {
      throw new FileRejectedException(FileRejectedException.Reason.MALWARE_DETECTED);
    }

    @GetMapping("/too-big")
    String tooBig() throws org.springframework.web.multipart.MaxUploadSizeExceededException {
      throw new org.springframework.web.multipart.MaxUploadSizeExceededException(6 * 1024 * 1024);
    }

    @GetMapping("/storage-down")
    String storageDown() {
      throw new UpstreamUnavailableException(
          "storage", new java.io.IOException("conexión rechazada a host-interno:8333"));
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Probe())
          .setControllerAdvice(new ApiExceptionHandler())
          .build();

  @Test
  void anUnexpectedFailureIsA500WithTheEnvelopeAndNoInternalDetail() throws Exception {
    String body =
        mvc.perform(get("/boom").requestAttr(TRACE_ATTRIBUTE, "traza-de-prueba"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.traceId").value("traza-de-prueba"))
            .andExpect(jsonPath("$.error.timestamp").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("secreta", "IllegalStateException");
  }

  // ADR-0021: el motivo viaja como detalle para que la interfaz diga qué corregir.
  @Test
  void aRejectedFileIsA422WithItsReason() throws Exception {
    mvc.perform(get("/rejected-file"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("FILE_REJECTED"))
        .andExpect(jsonPath("$.error.details[0].field").value("file"))
        .andExpect(jsonPath("$.error.details[0].code").value("MALWARE_DETECTED"));
  }

  // Lo que pasa del límite de multipart recibe el mismo contrato que el rechazo por tamaño del caso
  // de uso, no un 413 genérico.
  @Test
  void anUploadOverTheMultipartLimitIsAFileRejectedTooLarge() throws Exception {
    mvc.perform(get("/too-big"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("FILE_REJECTED"))
        .andExpect(jsonPath("$.error.details[0].code").value("TOO_LARGE"));
  }

  @Test
  void anUnavailableDependencyIsA503WithoutItsInternalDetail() throws Exception {
    String body =
        mvc.perform(get("/storage-down"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("host-interno", "8333", "storage");
  }

  @Test
  void aResponseStatusExceptionKeepsItsStatusAndGetsAGenericCode() throws Exception {
    mvc.perform(get("/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(get("/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("REQUEST_REJECTED"));
  }

  @Test
  void aWrongMethodIsA405WithTheEnvelope() throws Exception {
    mvc.perform(post("/boom"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
  }

  @Test
  void anUnknownRouteIsA404WithTheEnvelope() throws Exception {
    mvc.perform(get("/no-existe"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void everyStatusFamilyMapsToAStableCodeAndMessage() {
    assertThat(ApiExceptionHandler.code(HttpStatus.BAD_REQUEST)).isEqualTo("REQUEST_INVALID");
    assertThat(ApiExceptionHandler.code(HttpStatus.NOT_FOUND)).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(ApiExceptionHandler.code(HttpStatus.METHOD_NOT_ALLOWED))
        .isEqualTo("METHOD_NOT_ALLOWED");
    assertThat(ApiExceptionHandler.code(HttpStatus.NOT_ACCEPTABLE))
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(ApiExceptionHandler.code(HttpStatus.UNSUPPORTED_MEDIA_TYPE))
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(ApiExceptionHandler.code(HttpStatus.INTERNAL_SERVER_ERROR))
        .isEqualTo("INTERNAL_ERROR");
    assertThat(ApiExceptionHandler.code(HttpStatus.CONFLICT)).isEqualTo("REQUEST_REJECTED");
    assertThat(ApiExceptionHandler.code(HttpStatus.BAD_GATEWAY)).isEqualTo("INTERNAL_ERROR");
    assertThat(ApiExceptionHandler.message(HttpStatus.BAD_REQUEST))
        .isEqualTo("La petición no es válida.");
    assertThat(ApiExceptionHandler.message(HttpStatus.NOT_FOUND))
        .isEqualTo("El recurso no existe.");
    assertThat(ApiExceptionHandler.message(HttpStatus.METHOD_NOT_ALLOWED))
        .isEqualTo("Método no permitido.");
    assertThat(ApiExceptionHandler.message(HttpStatus.UNSUPPORTED_MEDIA_TYPE))
        .isEqualTo("Tipo de contenido no admitido.");
    assertThat(ApiExceptionHandler.message(HttpStatus.CONFLICT))
        .isEqualTo("La petición fue rechazada.");
    assertThat(ApiExceptionHandler.message(HttpStatus.BAD_GATEWAY)).isEqualTo("Error interno.");
  }

  @Test
  void aFailedValidationIsA400WithEveryFieldAndNeverTheValue() throws Exception {
    mvc.perform(get("/invalid").requestAttr(TRACE_ATTRIBUTE, "traza-de-prueba"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.traceId").value("traza-de-prueba"))
        .andExpect(jsonPath("$.error.details.length()").value(2))
        .andExpect(jsonPath("$.error.details[0].field").value("fullName"))
        .andExpect(jsonPath("$.error.details[0].code").value("REQUIRED"))
        .andExpect(jsonPath("$.error.details[1].field").value("bio"))
        .andExpect(jsonPath("$.error.details[1].code").value("TOO_LONG"));
  }

  @Test
  void anOrdinaryErrorCarriesNoDetailsField() throws Exception {
    mvc.perform(get("/missing")).andExpect(jsonPath("$.error.details").doesNotExist());
  }

  @Test
  void aDuplicateIsA409WithItsOwnCodeDistinctFromAStaleVersion() throws Exception {
    mvc.perform(get("/duplicate").requestAttr(TRACE_ATTRIBUTE, "traza-de-prueba"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ALREADY_EXISTS"))
        .andExpect(jsonPath("$.error.traceId").value("traza-de-prueba"));
  }

  @Test
  void aMissingCapabilityIsA403WithItsOwnCodeAndSaysNothingAboutWhichOne() throws Exception {
    String body =
        mvc.perform(get("/forbidden").requestAttr(TRACE_ATTRIBUTE, "traza-de-prueba"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN_CAPABILITY"))
            .andExpect(jsonPath("$.error.traceId").value("traza-de-prueba"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("CANDIDATE", "RECRUITER");
  }

  @Test
  void aNotFoundIsA404WithTheGenericCodeAndNoDetailOfTheResource() throws Exception {
    String body =
        mvc.perform(get("/absent").requestAttr(TRACE_ATTRIBUTE, "traza-de-prueba"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.error.traceId").value("traza-de-prueba"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("existe para");
  }

  @Test
  void aStaleStateIsA409WithItsOwnStableCode() throws Exception {
    mvc.perform(get("/stale").requestAttr(TRACE_ATTRIBUTE, "traza-de-prueba"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STALE_STATE"))
        .andExpect(jsonPath("$.error.traceId").value("traza-de-prueba"));
  }
}
