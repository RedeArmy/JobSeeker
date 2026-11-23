package com.usac.jobseeker.app.infrastructure.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Un único documento OpenAPI para todo el desplegable (ADR-0010). Los módulos aportan sus
 * controladores anotados, etiquetados por módulo (`@Tag`); aquí solo va lo común: la información
 * del documento, el esquema de seguridad y el sobre de error que todas las respuestas de error
 * comparten.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

  static final String ERROR_ENVELOPE = "ErrorEnvelope";

  @Bean
  OpenAPI jobSeekerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("JobSeeker API")
                .version("v1")
                .description(
                    "ATS con CRM y motor de compatibilidad CV-vacante. Autenticación con un Bearer"
                        + " emitido por Clavaris."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token de acceso de Clavaris (RS256, de 5 minutos)."))
                .addSchemas(ERROR_ENVELOPE, errorEnvelope()));
  }

  /** El sobre de `api-contract-overview.md` §6: lo usan todas las respuestas de error. */
  private static Schema<?> errorEnvelope() {
    Schema<?> error =
        new ObjectSchema()
            .addProperty(
                "code",
                new StringSchema()
                    .description("Código de error estable, en mayúsculas y propio del dominio.")
                    .example("RATE_LIMIT_EXCEEDED"))
            .addProperty(
                "message", new StringSchema().description("Mensaje legible, sin datos sensibles."))
            .addProperty(
                "traceId",
                new StringSchema().description("Correlaciona la respuesta con el registro."))
            .addProperty(
                "timestamp",
                new StringSchema().format("date-time").description("Momento del error, en UTC."))
            .required(java.util.List.of("code", "message", "traceId", "timestamp"));
    return new ObjectSchema()
        .description("Sobre estándar de error de la API.")
        .addProperty("error", error)
        .required(java.util.List.of("error"));
  }
}
