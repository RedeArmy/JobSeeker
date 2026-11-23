package com.usac.jobseeker.app.infrastructure.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reglas mínimas del spec exportado (ADR-0010, decisión 4): lo que convierte «documentado
 * profesionalmente» en algo verificable. Un spec que las incumple rompe el build, igual que el
 * formato o el análisis estático.
 *
 * <p>Son propias y no un ruleset de la comunidad porque comprueban convenciones de este proyecto
 * (sobre de error, versionado, un `@Tag` por módulo) que ninguno conoce.
 */
final class OpenApiRules {

  static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorEnvelope";

  private OpenApiRules() {}

  /** Cada incumplimiento, con la operación afectada; vacío si el spec cumple todo. */
  static List<String> violations(OpenAPI spec) {
    List<String> found = new ArrayList<>();
    if (spec.getComponents() == null
        || spec.getComponents().getSchemas() == null
        || !spec.getComponents().getSchemas().containsKey("ErrorEnvelope")) {
      found.add("falta el esquema ErrorEnvelope");
    }
    if (spec.getInfo() == null || isBlank(spec.getInfo().getTitle())) {
      found.add("el documento no tiene título");
    }
    if (spec.getPaths() == null || spec.getPaths().isEmpty()) {
      found.add("el documento no declara ninguna ruta");
      return found;
    }
    spec.getPaths().forEach((path, item) -> checkPath(path, item, found));
    return found;
  }

  private static void checkPath(String path, PathItem item, List<String> found) {
    if (!path.startsWith("/api/v1/")) {
      found.add(path + ": toda ruta cuelga de /api/v1");
    }
    item.readOperationsMap()
        .forEach((method, operation) -> checkOperation(method + " " + path, operation, found));
  }

  private static void checkOperation(String name, Operation operation, List<String> found) {
    if (isBlank(operation.getSummary())) {
      found.add(name + ": falta `summary`");
    }
    if (operation.getTags() == null || operation.getTags().isEmpty()) {
      found.add(name + ": falta la etiqueta del módulo");
    }
    if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
      found.add(name + ": declara su requisito de seguridad");
    }
    Map<String, ApiResponse> responses = operation.getResponses();
    if (responses == null || responses.isEmpty()) {
      found.add(name + ": no declara respuestas");
      return;
    }
    if (!responses.containsKey("401") || !responses.containsKey("429")) {
      found.add(name + ": debe documentar 401 y 429");
    }
    responses.forEach(
        (code, response) -> {
          if (isError(code) && !usesErrorEnvelope(response)) {
            found.add(name + ": la respuesta " + code + " no usa ErrorEnvelope");
          }
        });
  }

  private static boolean isError(String code) {
    return code.startsWith("4") || code.startsWith("5");
  }

  private static boolean usesErrorEnvelope(ApiResponse response) {
    if (response.getContent() == null) {
      return false;
    }
    return response.getContent().values().stream()
        .map(media -> media.getSchema())
        .anyMatch(OpenApiRules::referencesErrorEnvelope);
  }

  private static boolean referencesErrorEnvelope(Schema<?> schema) {
    return schema != null && ERROR_SCHEMA_REF.equals(schema.get$ref());
  }

  private static boolean isBlank(String text) {
    return text == null || text.isBlank();
  }
}
