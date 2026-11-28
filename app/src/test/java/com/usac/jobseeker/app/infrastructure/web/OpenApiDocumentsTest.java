package com.usac.jobseeker.app.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * El validador debe rechazar lo que no es OpenAPI 3.1, no solo aceptar el documento real: sin estas
 * pruebas, un esquema mal cargado lo dejaría pasar todo en silencio.
 */
class OpenApiDocumentsTest {

  private static final String VALID =
      """
      {"openapi":"3.1.0","info":{"title":"T","version":"1"},
       "paths":{"/api/v1/x":{"get":{"responses":{"200":{"description":"ok"}}}}}}
      """;

  @Test
  void aMinimalValidDocumentPasses() {
    assertThat(OpenApiDocuments.violationsOfTheOpenApi31Schema(VALID)).isEmpty();
    assertThat(OpenApiDocuments.parse(VALID).getPaths()).containsKey("/api/v1/x");
  }

  @Test
  void aDocumentWithoutInfoOrWithAWrongVersionIsRejected() {
    assertThat(
            OpenApiDocuments.violationsOfTheOpenApi31Schema("{\"openapi\":\"3.1.0\",\"paths\":{}}"))
        .as("falta `info`")
        .isNotEmpty();
    assertThat(
            OpenApiDocuments.violationsOfTheOpenApi31Schema(VALID.replace("\"3.1.0\"", "\"2.0\"")))
        .as("no es 3.1")
        .isNotEmpty();
  }

  // `schema-base` valida además cada objeto Schema contra el dialecto de OpenAPI.
  @Test
  void anInvalidSchemaObjectIsRejected() {
    String badSchema =
        """
        {"openapi":"3.1.0","info":{"title":"T","version":"1"},
         "components":{"schemas":{"X":{"type":"no-es-un-tipo"}}}}
        """;

    assertThat(OpenApiDocuments.violationsOfTheOpenApi31Schema(badSchema)).isNotEmpty();
  }

  @Test
  void anOperationWithoutResponsesIsRejected() {
    assertThat(
            OpenApiDocuments.violationsOfTheOpenApi31Schema(
                VALID.replace("\"responses\":{\"200\":{\"description\":\"ok\"}}", "\"x\":1")))
        .isNotEmpty();
  }
}
