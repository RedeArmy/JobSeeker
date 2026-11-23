package com.usac.jobseeker.app.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sin tocar nada, el spec NO se sirve: describe toda la superficie de ataque y el ADR-0010 exige
 * que nunca esté público en producción. Solo el perfil `dev` lo enciende.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
class OpenApiDisabledByDefaultIntegrationTest {

  @Autowired private MockMvc mvc;

  @Test
  void theSpecAndTheUiAreNotServedByDefault() throws Exception {
    for (String path :
        new String[] {
          "/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html"
        }) {
      var response = mvc.perform(get(path)).andReturn().getResponse();

      assertThat(response.getStatus()).as(path).isNotEqualTo(200);
      assertThat(response.getContentAsString())
          .as(path)
          .doesNotContain("\"openapi\"", "swagger-ui-bundle");
    }
  }
}
