package com.usac.jobseeker.app.infrastructure.web;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Leer y validar el OpenAPI exportado (ADR-0010, decisión 4) sin swagger-parser, que sigue sobre
 * swagger-core 2 y Jackson 2 y no convive con springdoc 3.2.1 (swagger-core 3, Jackson 3).
 *
 * <ul>
 *   <li>La **validez** se comprueba contra el esquema JSON oficial de OpenAPI 3.1, copiado en los
 *       recursos de prueba (`openapi-3.1/`): el validador resuelve sus identificadores contra esos
 *       archivos y nunca contra la red.
 *   <li>Las **reglas del proyecto** se aplican sobre el modelo de swagger-core 3, el mismo con el
 *       que springdoc lo genera.
 * </ul>
 */
final class OpenApiDocuments {

  private static final String ROOT = "https://spec.openapis.org/oas/3.1/";
  private static final String SCHEMA_BASE = ROOT + "schema-base/2022-10-07";

  // Cada identificador del esquema oficial, con el archivo que lo contiene.
  private static final Map<String, String> OFFICIAL_SCHEMA =
      Map.of(
          SCHEMA_BASE,
          "schema-base-2022-10-07.json",
          ROOT + "schema/2022-10-07",
          "schema-2022-10-07.json",
          ROOT + "dialect/base",
          "dialect-base.json",
          ROOT + "meta/base",
          "meta-base.json");

  private static final Schema OPENAPI_31 =
      SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              builder ->
                  builder.schemas(
                      id -> {
                        String file = OFFICIAL_SCHEMA.get(id);
                        return file == null ? null : resource(file);
                      }))
          .getSchema(SchemaLocation.of(SCHEMA_BASE));

  private OpenApiDocuments() {}

  /** Lo que el documento incumple del esquema oficial de OpenAPI 3.1; vacío si es válido. */
  static List<String> violationsOfTheOpenApi31Schema(String json) {
    return OPENAPI_31.validate(json, InputFormat.JSON).stream()
        .map(OpenApiDocuments::describe)
        .toList();
  }

  /** El documento en el modelo de swagger-core 3, para aplicarle las reglas del proyecto. */
  static OpenAPI parse(String json) {
    return Json31.mapper().readValue(json, OpenAPI.class);
  }

  private static String describe(Error error) {
    return error.getInstanceLocation() + ": " + error.getMessage();
  }

  private static String resource(String file) {
    try (InputStream in = OpenApiDocuments.class.getResourceAsStream("/openapi-3.1/" + file)) {
      if (in == null) {
        throw new IllegalStateException("Falta el esquema oficial de OpenAPI 3.1: " + file);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
