package com.usac.jobseeker.app.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El OpenAPI que genera la aplicación real (ADR-0010): se exporta, se valida contra la
 * especificación y se le aplican las reglas del proyecto. Si un endpoint se escribe sin documentar,
 * este test rompe el build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class OpenApiSpecIntegrationTest {

  @DynamicPropertySource
  static void enableDocs(DynamicPropertyRegistry registry) {
    registry.add("springdoc.api-docs.enabled", () -> "true");
  }

  @Autowired private MockMvc mvc;

  private String export() throws Exception {
    return mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
  }

  @Test
  void theGeneratedSpecIsValidOpenApi31() throws Exception {
    String spec = export();

    assertThat(OpenApiDocuments.violationsOfTheOpenApi31Schema(spec))
        .as("incumplimientos del esquema oficial de OpenAPI 3.1")
        .isEmpty();
    assertThat(OpenApiDocuments.parse(spec).getOpenapi()).startsWith("3.1");
  }

  @Test
  void theGeneratedSpecMeetsEveryProjectRule() throws Exception {
    OpenAPI spec = OpenApiDocuments.parse(export());

    assertThat(OpenApiRules.violations(spec)).isEmpty();
  }

  @Test
  void theTemplateEndpointIsDocumentedWithItsModuleTagAndBusinessDescriptions() throws Exception {
    OpenAPI spec = OpenApiDocuments.parse(export());

    var operation = spec.getPaths().get("/api/v1/me").getGet();
    assertThat(operation.getSummary()).isEqualTo("Consultar mi cuenta");
    assertThat(operation.getTags()).containsExactly("auth");
    assertThat(operation.getResponses()).containsKeys("200", "401", "403", "404", "429");
    var schema = spec.getComponents().getSchemas().get("MyAccountResponse");
    assertThat(schema.getProperties()).containsKeys("id", "capabilities");
    assertThat(
            ((io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("capabilities"))
                .getDescription())
        .contains("independientes");
  }

  @Test
  void everyOperationOfTheFourProfileCollectionsDocumentsTheSharedErrorResponses()
      throws Exception {
    OpenAPI spec = OpenApiDocuments.parse(export());
    String base = "/api/v1/me/professional-profile/";

    for (String collection :
        java.util.List.of("experiences", "education", "certifications", "projects")) {
      var add = spec.getPaths().get(base + collection).getPost();
      var replace = spec.getPaths().get(base + collection + "/{id}").getPut();
      var remove = spec.getPaths().get(base + collection + "/{id}").getDelete();
      assertThat(add.getResponses())
          .as("alta de " + collection)
          .containsKeys("201", "400", "401", "403", "404", "409", "429");
      assertThat(replace.getResponses())
          .as("reemplazo de " + collection)
          .containsKeys("200", "400", "401", "403", "404", "409", "429");
      assertThat(remove.getResponses())
          .as("baja de " + collection)
          .containsKeys("200", "400", "401", "403", "404", "409", "429");
      for (var operation : java.util.List.of(add, replace, remove)) {
        assertThat(operation.getTags()).containsExactly("cv");
        assertThat(operation.getSecurity()).isNotEmpty();
        // La anotación compuesta debe apuntar al sobre estándar, no copiar su forma.
        var errorContent = operation.getResponses().get("409").getContent();
        assertThat(errorContent.values())
            .singleElement()
            .satisfies(media -> assertThat(media.getSchema().get$ref()).endsWith("ErrorEnvelope"));
      }
    }
  }

  @Test
  void theSpecIsASingleDocumentForTheWholeDeployable() throws Exception {
    OpenAPI spec = OpenApiDocuments.parse(export());

    assertThat(spec.getInfo().getTitle()).isEqualTo("JobSeeker API");
    assertThat(spec.getComponents().getSecuritySchemes()).containsKey("bearer");
  }
}
