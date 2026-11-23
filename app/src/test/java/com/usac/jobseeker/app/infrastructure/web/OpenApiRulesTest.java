package com.usac.jobseeker.app.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Las reglas del spec también se prueban: un linter que nunca ha rechazado nada no demuestra que
 * pueda hacerlo. Aquí se parte de un spec correcto y se rompe una cosa por vez.
 */
class OpenApiRulesTest {

  private static ApiResponse envelopeResponse() {
    Schema<?> ref = new Schema<>().$ref(OpenApiRules.ERROR_SCHEMA_REF);
    return new ApiResponse()
        .description("error")
        .content(new Content().addMediaType("application/json", new MediaType().schema(ref)));
  }

  private static Operation goodOperation() {
    return new Operation()
        .summary("Consultar mi cuenta")
        .addTagsItem("auth")
        .addSecurityItem(new SecurityRequirement().addList("bearer"))
        .responses(
            new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok"))
                .addApiResponse("401", envelopeResponse())
                .addApiResponse("429", envelopeResponse()));
  }

  private static OpenAPI goodSpec() {
    return new OpenAPI()
        .info(new Info().title("JobSeeker API").version("v1"))
        .components(new Components().addSchemas("ErrorEnvelope", new ObjectSchema()))
        .paths(new Paths().addPathItem("/api/v1/me", new PathItem().get(goodOperation())));
  }

  private static OpenAPI withOperation(Operation operation) {
    OpenAPI spec = goodSpec();
    spec.getPaths().get("/api/v1/me").get(operation);
    return spec;
  }

  @Test
  void aCorrectSpecHasNoViolations() {
    assertThat(OpenApiRules.violations(goodSpec())).isEmpty();
  }

  @Test
  void aRouteOutsideTheVersionedPrefixIsRejected() {
    OpenAPI spec = goodSpec();
    spec.getPaths().addPathItem("/me", new PathItem().get(goodOperation()));

    assertThat(OpenApiRules.violations(spec))
        .anyMatch(v -> v.contains("/me") && v.contains("/api/v1"));
  }

  @Test
  void anOperationWithoutSummaryIsRejected() {
    assertThat(OpenApiRules.violations(withOperation(goodOperation().summary(" "))))
        .anyMatch(v -> v.contains("summary"));
    assertThat(OpenApiRules.violations(withOperation(goodOperation().summary(null))))
        .anyMatch(v -> v.contains("summary"));
  }

  @Test
  void anOperationWithoutAModuleTagIsRejected() {
    assertThat(OpenApiRules.violations(withOperation(goodOperation().tags(List.of()))))
        .anyMatch(v -> v.contains("etiqueta"));
    assertThat(OpenApiRules.violations(withOperation(goodOperation().tags(null))))
        .anyMatch(v -> v.contains("etiqueta"));
  }

  @Test
  void anOperationWithoutASecurityRequirementIsRejected() {
    Operation open = goodOperation().security(null);

    assertThat(OpenApiRules.violations(withOperation(open))).anyMatch(v -> v.contains("seguridad"));
  }

  @Test
  void anOperationMustDocumentTheUnauthenticatedAndTheRateLimitedAnswers() {
    Operation without401 = goodOperation();
    without401.getResponses().remove("401");
    Operation without429 = goodOperation();
    without429.getResponses().remove("429");

    assertThat(OpenApiRules.violations(withOperation(without401)))
        .anyMatch(v -> v.contains("401 y 429"));
    assertThat(OpenApiRules.violations(withOperation(without429)))
        .anyMatch(v -> v.contains("401 y 429"));
  }

  @Test
  void anErrorResponseThatDoesNotUseTheStandardEnvelopeIsRejected() {
    Operation plain = goodOperation();
    plain.getResponses().addApiResponse("404", new ApiResponse().description("no hay"));
    Operation wrongSchema = goodOperation();
    wrongSchema
        .getResponses()
        .addApiResponse(
            "500",
            new ApiResponse()
                .content(
                    new Content()
                        .addMediaType(
                            "application/json", new MediaType().schema(new ObjectSchema()))));

    assertThat(OpenApiRules.violations(withOperation(plain)))
        .anyMatch(v -> v.contains("404") && v.contains("ErrorEnvelope"));
    assertThat(OpenApiRules.violations(withOperation(wrongSchema)))
        .anyMatch(v -> v.contains("500") && v.contains("ErrorEnvelope"));
  }

  @Test
  void aSuccessResponseDoesNotNeedTheErrorEnvelope() {
    Operation operation = goodOperation();
    operation.getResponses().addApiResponse("204", new ApiResponse().description("sin cuerpo"));

    assertThat(OpenApiRules.violations(withOperation(operation))).isEmpty();
  }

  @Test
  void aSpecWithoutTheErrorEnvelopeTitleOrRoutesIsRejected() {
    OpenAPI noEnvelope = goodSpec().components(new Components());
    OpenAPI noTitle = goodSpec().info(new Info());
    OpenAPI noComponents = goodSpec().components(null);
    OpenAPI noPaths = goodSpec().paths(new Paths());

    assertThat(OpenApiRules.violations(noEnvelope)).anyMatch(v -> v.contains("ErrorEnvelope"));
    assertThat(OpenApiRules.violations(noComponents)).anyMatch(v -> v.contains("ErrorEnvelope"));
    assertThat(OpenApiRules.violations(noTitle)).anyMatch(v -> v.contains("título"));
    assertThat(OpenApiRules.violations(noPaths)).anyMatch(v -> v.contains("ninguna ruta"));
  }

  @Test
  void anOperationWithoutResponsesIsRejectedWithoutFailing() {
    assertThat(
            OpenApiRules.violations(withOperation(goodOperation().responses(new ApiResponses()))))
        .anyMatch(v -> v.contains("no declara respuestas"));
  }
}
