package com.usac.jobseeker.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.CandidateProfileEntity;
import com.usac.jobseeker.cv.infrastructure.adapter.out.persistence.CandidateProfileRepository;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SpikeIntegrationTest {

  @Container
  static PostgreSQLContainer pg =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired CandidateProfileRepository profiles;
  @Autowired MockMvc mvc;
  @Autowired TemplateEngine templates;
  @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

  @Autowired(required = false)
  EmbeddingModel embeddingModel;

  private static float[] vec(float first) {
    float[] v = new float[1024];
    v[0] = first;
    v[1] = 1f;
    return v;
  }

  @Test
  void migrationsRunInTimestampOrderAndVectorWorks() {
    List<String> versions =
        jdbc.queryForList(
            "select version from flyway_schema_history order by installed_rank", String.class);
    assertThat(versions).containsExactly("1", "20261121000100");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'idx_cp_embedding_hnsw'",
                String.class))
        .contains("USING hnsw")
        .contains("WHERE");

    UUID near = UUID.randomUUID();
    UUID far = UUID.randomUUID();
    String hash = "a".repeat(64);
    profiles.save(new CandidateProfileEntity(near, true, vec(0.1f), "qwen3-embedding:0.6b", hash));
    profiles.save(new CandidateProfileEntity(far, true, vec(50f), "qwen3-embedding:0.6b", hash));
    profiles.save(
        new CandidateProfileEntity(
            UUID.randomUUID(), false, vec(0.1f), "qwen3-embedding:0.6b", hash));
    List<CandidateProfileEntity> top = profiles.nearest(vec(0.1f), PageRequest.of(0, 2));
    assertThat(top).hasSize(2);
    assertThat(top.get(0).getId()).isEqualTo(near);
    assertThat(top.get(0).getEmbedding()).hasSize(1024);

    // CHECK todo-o-nada del bloque embedding (data-model §5.1)
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into candidate_profiles(id, embedding_model) values (?, 'x')",
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void bucket4jOverRedisLimitsPerKey() {
    RedisClient client =
        RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    var proxy = Bucket4jLettuce.casBasedBuilder(client).build();
    BucketConfiguration cfg =
        BucketConfiguration.builder()
            .addLimit(
                Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofMinutes(1)).build())
            .build();
    var a = proxy.builder().build("ratelimit:test:a".getBytes(StandardCharsets.UTF_8), () -> cfg);
    var b = proxy.builder().build("ratelimit:test:b".getBytes(StandardCharsets.UTF_8), () -> cfg);
    int granted = 0;
    for (int i = 0; i < 5; i++) if (a.tryConsume(1)) granted++;
    assertThat(granted).isEqualTo(3);
    assertThat(b.tryConsume(1)).isTrue();
    client.shutdown();
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void indexedSessionsAreFoundByPrincipal() {
    FindByIndexNameSessionRepository repo = sessions;
    for (int i = 0; i < 2; i++) {
      Session s = repo.createSession();
      s.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "cuenta-1");
      repo.save(s);
    }
    var found = repo.findByPrincipalName("cuenta-1");
    assertThat(found).hasSize(2);
    found.keySet().forEach(id -> repo.deleteById((String) id));
    assertThat(repo.findByPrincipalName("cuenta-1")).isEmpty();
  }

  @Test
  void thymeleafRendersAndOpenHtmlToPdfProducesAPdf() throws Exception {
    Context ctx = new Context();
    ctx.setVariable("nombre", "María José Pérez");
    ctx.setVariable("resumen", "Ingeniera en Sistemas — acentos y ñ: señal, canción");
    String html = templates.process("cv/sample", ctx);
    assertThat(html).contains("María José Pérez");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfRendererBuilder b = new PdfRendererBuilder();
    b.withHtmlContent(html, null);
    b.toStream(out);
    b.run();
    byte[] pdf = out.toByteArray();
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    assertThat(pdf.length).isGreaterThan(1000);
  }

  @Test
  void actuatorOpenApiAndAiBeansAreWired() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    String metrics =
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(metrics).contains("jvm_memory_used_bytes").contains("hikaricp_connections");
    String api =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(api).contains("/api/v1/spike/ping");
    assertThat(embeddingModel).isNotNull();
  }

  @Test
  void structuredLoggingEmitsJson(CapturedOutput output) {
    LoggerFactory.getLogger("spike").info("evento=prueba_json traceId={}", "abc123");
    assertThat(output.getOut())
        .contains("\"@timestamp\"")
        .contains("\"message\":\"evento=prueba_json traceId=abc123\"")
        .contains("\"logger_name\":\"spike\"");
  }
}
