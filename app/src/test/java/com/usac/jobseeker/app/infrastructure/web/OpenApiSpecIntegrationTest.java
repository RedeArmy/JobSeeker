package com.usac.jobseeker.app.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
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
    ParseOptions options = new ParseOptions();
    options.setResolve(true);

    SwaggerParseResult result = new OpenAPIV3Parser().readContents(export(), null, options);

    assertThat(result.getMessages()).as("errores del parser").isEmpty();
    assertThat(result.getOpenAPI().getOpenapi()).startsWith("3.1");
  }

  @Test
  void theGeneratedSpecMeetsEveryProjectRule() throws Exception {
    OpenAPI spec = new OpenAPIV3Parser().readContents(export(), null, null).getOpenAPI();

    assertThat(OpenApiRules.violations(spec)).isEmpty();
  }

  @Test
  void theTemplateEndpointIsDocumentedWithItsModuleTagAndBusinessDescriptions() throws Exception {
    OpenAPI spec = new OpenAPIV3Parser().readContents(export(), null, null).getOpenAPI();

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
  void theSpecIsASingleDocumentForTheWholeDeployable() throws Exception {
    OpenAPI spec = new OpenAPIV3Parser().readContents(export(), null, null).getOpenAPI();

    assertThat(spec.getInfo().getTitle()).isEqualTo("JobSeeker API");
    assertThat(spec.getComponents().getSecuritySchemes()).containsKey("bearer");
  }
}
