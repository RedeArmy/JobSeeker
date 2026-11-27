package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizeProfileDataCommand;
import com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizeProfileDataUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * El CV y la foto de perfil de punta a punta (ADR-0021, `tdd/cv-module.md` §10.7 y §10.8): la API
 * real con tokens firmados, Postgres, Redis y SeaweedFS reales. El antivirus es un doble que marca
 * la cadena de prueba EICAR: el contrato con ClamAV real lo cubre {@code ClamAvContractTest}, que
 * no corre en cada push.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({InfrastructureTestConfiguration.class, CvFileEndpointIntegrationTest.FakeScanner.class})
class CvFileEndpointIntegrationTest {

  private static final String PROFILE = "/api/v1/me/professional-profile";
  private static final String CANDIDATE = "/api/v1/me/candidate-profile";
  private static final String CV = CANDIDATE + "/cv";
  private static final String PHOTO = PROFILE + "/photo";
  private static final byte[] PDF =
      "%PDF-1.7\ncurrículum de prueba\n%%EOF".getBytes(StandardCharsets.UTF_8);
  private static final String ACCESS = "prueba-acceso";
  private static final String SECRET = "prueba-secreto-1234567890";

  private static final StubIssuer ISSUER = startIssuer();
  private static final TestTokens KEYS = TestTokens.withKey("kid-cv-files");
  private static final GenericContainer<?> SEAWEED = startSeaweed();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static StubIssuer startIssuer() {
    try {
      return new StubIssuer();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static GenericContainer<?> startSeaweed() {
    GenericContainer<?> seaweed =
        new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.53"))
            .withCopyToContainer(
                Transferable.of(
                    "{\"identities\":[{\"name\":\"prueba\",\"credentials\":[{\"accessKey\":\""
                        + ACCESS
                        + "\",\"secretKey\":\""
                        + SECRET
                        + "\"}],\"actions\":[\"Admin\",\"Read\",\"Write\",\"List\"]}]}"),
                "/etc/seaweedfs/s3.json")
            .withCommand(
                "server", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/healthz").forPort(8333).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));
    seaweed.start();
    return seaweed;
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.clavaris.issuer-uri", ISSUER::issuerUri);
    registry.add("jobseeker.auth.clavaris.client-id", () -> TestTokens.CLIENT_ID);
    registry.add("jobseeker.auth.clavaris.client-secret", () -> "secreto-de-prueba");
    registry.add(
        "jobseeker.storage.endpoint",
        () -> "http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(8333));
    registry.add("jobseeker.storage.bucket", () -> "jobseeker-cv-test");
    registry.add("jobseeker.storage.access-key", () -> ACCESS);
    registry.add("jobseeker.storage.secret-key", () -> SECRET);
    registry.add("jobseeker.storage.create-bucket-if-missing", () -> "true");
    // La bandeja se entrega a mano (deliverEvents): el sondeador programado no debe competir.
    registry.add("jobseeker.outbox.poll-interval", () -> "1h");
    registry.add("jobseeker.matching.queue.poll-interval", () -> "1h");
  }

  /** Un antivirus de prueba: infectado si el contenido trae la cadena EICAR. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FakeScanner {
    @Bean
    @Primary
    MalwareScanPort fakeMalwareScanner() {
      return content ->
          new String(content, StandardCharsets.ISO_8859_1).contains("EICAR")
              ? new MalwareScanPort.ScanVerdict.Infected("Eicar-Test-Signature")
              : new MalwareScanPort.ScanVerdict.Clean();
    }
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
    SEAWEED.stop();
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectStoragePort storage;
  @Autowired private AnonymizeProfileDataUseCase anonymize;
  @Autowired private MeterRegistry meters;
  @Autowired private List<OutboxEventConsumer> consumers;
  @Autowired private com.usac.jobseeker.app.infrastructure.outbox.OutboxStore store;
  @Autowired private PlatformTransactionManager transactions;

  @BeforeEach
  void publishTheKeys() {
    ISSUER.jwks = KEYS.jwks();
    ISSUER.jwksStatus = 200;
  }

  @Test
  void aCandidateUploadsTheirCvAndDownloadsItThroughAShortLivedSignedUrl() throws Exception {
    UUID me = withCandidateProfile();

    String body =
        mvc.perform(authed(multipart(CV).file(file("Mi CV final.pdf", PDF)), me))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("PDF"))
            .andExpect(jsonPath("$.name").value("Mi CV final.pdf"))
            .andExpect(jsonPath("$.current").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String fileId = JsonPath.read(body, "$.id");

    mvc.perform(authed(get(CV), me))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.current.id").value(fileId))
        .andExpect(jsonPath("$.versions.length()").value(1))
        .andExpect(jsonPath("$.maxVersions").value(20));
    String location =
        mvc.perform(authed(get(CV + "/" + fileId + "/download"), me))
            .andExpect(status().isFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getHeader("Location");
    HttpResponse<byte[]> download =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(location)).build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.body()).isEqualTo(PDF);
    assertThat(download.headers().firstValue("content-disposition").orElseThrow())
        .startsWith("attachment;");
    assertThat(location).doesNotContain(me.toString());
  }

  // ADR-0021 §2: el tipo sale del contenido y el antivirus decide antes de guardar nada.
  @Test
  void aFileThatIsNotACvOrCarriesMalwareIsRejectedAndNothingIsStored() throws Exception {
    UUID me = withCandidateProfile();
    byte[] malware =
        "%PDF-1.7\nX5O!P%@AP EICAR-STANDARD-ANTIVIRUS-TEST-FILE"
            .getBytes(StandardCharsets.US_ASCII);
    byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
    System.arraycopy(PDF, 0, tooLarge, 0, PDF.length);

    rejected(
        me,
        file("cv.pdf", "MZ ejecutable".getBytes(StandardCharsets.US_ASCII)),
        "UNSUPPORTED_TYPE");
    rejected(me, file("cv.pdf", malware), "MALWARE_DETECTED");
    rejected(me, file("cv.pdf", tooLarge), "TOO_LARGE");

    assertThat(versionsOf(me)).isZero();
  }

  // La versión de otro no existe para mí, y sin la capacidad de candidato no hay CV.
  @Test
  void aVersionOfAnotherAccountIsNotFoundAndARecruiterOnlyAccountIsForbidden() throws Exception {
    UUID owner = withCandidateProfile();
    String body =
        mvc.perform(authed(multipart(CV).file(file("cv.pdf", PDF)), owner))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String fileId = JsonPath.read(body, "$.id");
    UUID other = withCandidateProfile();
    UUID recruiter = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_recruiter_enabled) values (?, true)", recruiter);

    mvc.perform(authed(get(CV + "/" + fileId + "/download"), other))
        .andExpect(status().isNotFound());
    mvc.perform(authed(get(CV + "/no-es-un-uuid/download"), other))
        .andExpect(status().isNotFound());
    mvc.perform(authed(multipart(CV).file(file("cv.pdf", PDF)), recruiter))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN_CAPABILITY"));
  }

  // BR-DATA-05 y ADR-0021 §6: anonimizar borra el archivo del almacén por la bandeja.
  @Test
  void anonymizingTheAccountDeletesTheCvFromTheStorage() throws Exception {
    UUID me = withCandidateProfile();
    mvc.perform(authed(multipart(CV).file(file("cv.pdf", PDF)), me))
        .andExpect(status().isCreated());
    String key =
        jdbc.queryForObject(
            "select f.object_key from candidate_cv_files f join candidate_profiles p"
                + " on p.id = f.candidate_profile_id where p.account_id = ?",
            String.class,
            me);
    assertThat(storage.exists(new StoredObjectKey(key))).isTrue();

    anonymize.execute(new AnonymizeProfileDataCommand(new AccountId(me)));
    deliverEvents();

    assertThat(storage.exists(new StoredObjectKey(key))).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from candidate_cv_files f join candidate_profiles p"
                    + " on p.id = f.candidate_profile_id where p.account_id = ?"
                    + " and (f.object_key is not null or f.display_name is not null)",
                Integer.class,
                me))
        .isZero();
  }

  // ---- foto de perfil (tdd/cv-module.md §10.8) ----

  // Se guarda re-codificada y reducida, se sirve por la propia aplicación y reemplazarla borra la
  // anterior del almacén por la bandeja.
  @Test
  void aPhotoIsStoredReducedServedByTheAppAndReplacingItDeletesThePreviousOne() throws Exception {
    UUID me = withCandidateProfile();
    byte[] big = png(1600, 800);

    String body =
        mvc.perform(authed(multipart(PHOTO).file(file("yo.png", big)), me))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("PNG"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String first = photoKeyOf(me);
    assertThat(first).endsWith(JsonPath.<String>read(body, "$.tag"));

    byte[] served =
        mvc.perform(authed(get(PHOTO), me))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("Cache-Control", "max-age=300, private"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(served));
    assertThat(image.getWidth()).isEqualTo(512);
    assertThat(image.getHeight()).isEqualTo(256);

    mvc.perform(authed(multipart(PHOTO).file(file("otra.png", png(10, 10))), me))
        .andExpect(status().isCreated());
    deliverEvents();

    assertThat(photoKeyOf(me)).isNotEqualTo(first);
    assertThat(storage.exists(new StoredObjectKey(first))).isFalse();
  }

  @Test
  void aPhotoThatIsNotAnImageOrCarriesMalwareIsRejected() throws Exception {
    UUID me = withCandidateProfile();
    byte[] infected = png(4, 4);
    byte[] withEicar = new byte[infected.length + 5];
    System.arraycopy(infected, 0, withEicar, 0, infected.length);
    System.arraycopy("EICAR".getBytes(StandardCharsets.US_ASCII), 0, withEicar, infected.length, 5);

    for (var rejected :
        List.of(
            List.of(PDF, "UNSUPPORTED_TYPE"),
            List.of(withEicar, "MALWARE_DETECTED"),
            List.of(new byte[2 * 1024 * 1024 + 1], "TOO_LARGE"))) {
      mvc.perform(authed(multipart(PHOTO).file(file("f.png", (byte[]) rejected.get(0))), me))
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.error.code").value("FILE_REJECTED"));
    }
    mvc.perform(authed(get(PHOTO), me)).andExpect(status().isNotFound());
    assertThat(photoKeyOf(me)).isNull();
  }

  @Test
  void removingThePhotoAndAnonymizingTheAccountDeleteItFromTheStorage() throws Exception {
    UUID removing = withCandidateProfile();
    mvc.perform(authed(multipart(PHOTO).file(file("yo.png", png(20, 20))), removing))
        .andExpect(status().isCreated());
    String removed = photoKeyOf(removing);
    UUID leaving = withCandidateProfile();
    mvc.perform(authed(multipart(PHOTO).file(file("yo.png", png(20, 20))), leaving))
        .andExpect(status().isCreated());
    String anonymized = photoKeyOf(leaving);

    mvc.perform(authed(delete(PHOTO), removing)).andExpect(status().isNoContent());
    mvc.perform(authed(delete(PHOTO), removing)).andExpect(status().isNoContent());
    anonymize.execute(new AnonymizeProfileDataCommand(new AccountId(leaving)));
    deliverEvents();

    assertThat(storage.exists(new StoredObjectKey(removed))).isFalse();
    assertThat(storage.exists(new StoredObjectKey(anonymized))).isFalse();
    mvc.perform(authed(get(PHOTO), removing)).andExpect(status().isNotFound());
  }

  // ---- pantallas HTMX (ADR-0005) ----

  @Test
  void theCvScreenUploadsAVersionAndListsItWithItsDownloadLink() throws Exception {
    UUID me = withCandidateProfile();

    String page = screen(get("/app/candidate-cv").with(as(me)), 200);
    assertThat(page).contains("Aún no has subido tu CV", "hx-encoding=\"multipart/form-data\"");

    String fragment =
        screen(
            multipart("/app/candidate-cv").file(file("Mi CV.pdf", PDF)).with(as(me)).with(csrf()),
            200);
    assertThat(fragment).contains("Tu CV quedó guardado", "Mi CV.pdf", "Vigente", "1 de 20");
    String fileId =
        jdbc.queryForObject(
            "select f.id::text from candidate_cv_files f join candidate_profiles p"
                + " on p.id = f.candidate_profile_id where p.account_id = ?",
            String.class,
            me);
    assertThat(fragment).contains("/app/candidate-cv/" + fileId + "/download");
    mvc.perform(get("/app/candidate-cv/" + fileId + "/download").with(as(me)))
        .andExpect(status().isFound())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  // Un rechazo responde 422 con el motivo en castellano, sin repetir el nombre del archivo.
  @Test
  void theCvScreenExplainsWhyAFileWasRejected() throws Exception {
    UUID me = withCandidateProfile();

    String errors =
        screen(
            multipart("/app/candidate-cv")
                .file(file("secreto-ana.exe", "MZ".getBytes(StandardCharsets.US_ASCII)))
                .with(as(me))
                .with(csrf()),
            422);

    assertThat(errors).contains("Solo se aceptan CV en PDF").doesNotContain("secreto-ana");
    assertThat(versionsOf(me)).isZero();
  }

  @Test
  void theCvScreenWithoutACandidateProfileAsksToCreateItFirst() throws Exception {
    UUID me = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", me);

    assertThat(screen(get("/app/candidate-cv").with(as(me)), 200))
        .contains("Primero crea tu", "/app/candidate-profile");
  }

  @Test
  void theProfileScreenLoadsThePhotoSectionAndChangesThePhotoInPlace() throws Exception {
    UUID me = withCandidateProfile();
    assertThat(screen(get("/app/profile").with(as(me)), 200))
        .contains("hx-get=\"/app/profile/photo-section\"");
    assertThat(screen(get("/app/profile/photo-section").with(as(me)), 200))
        .contains("Aún no tienes foto");

    String uploaded =
        screen(
            multipart("/app/profile/photo")
                .file(file("yo.jpg", jpeg(30, 30)))
                .with(as(me))
                .with(csrf()),
            200);
    String key = photoKeyOf(me);
    assertThat(uploaded)
        .contains(
            "Tu foto quedó guardada", "/app/profile/photo?v=" + key.substring(key.length() - 12));
    mvc.perform(get("/app/profile/photo").with(as(me)))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"));

    String removed = screen(delete("/app/profile/photo").with(as(me)).with(csrf()), 200);
    assertThat(removed).contains("Quitamos tu foto", "Aún no tienes foto");
  }

  // El error vuelve a la propia sección (HX-Retarget), no a la zona de errores del formulario.
  @Test
  void aRejectedPhotoIsExplainedInsideThePhotoSection() throws Exception {
    UUID me = withCandidateProfile();

    var response =
        mvc.perform(
                multipart("/app/profile/photo").file(file("x.png", PDF)).with(as(me)).with(csrf()))
            .andExpect(status().isUnprocessableContent())
            .andExpect(header().string("HX-Retarget", "#photo-section"))
            .andExpect(header().string("HX-Reswap", "outerHTML"))
            .andReturn()
            .getResponse();

    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .contains("id=\"photo-section\"", "Solo se aceptan imágenes PNG o JPEG");
  }

  private String screen(
      org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus)
      throws Exception {
    return mvc.perform(request)
        .andExpect(status().is(expectedStatus))
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor
      as(UUID account) {
    return oidcLogin().idToken(token -> token.subject(account.toString()));
  }

  private String photoKeyOf(UUID account) {
    return jdbc.queryForObject(
        "select profile_photo_key from professional_profiles where account_id = ?",
        String.class,
        account);
  }

  private static byte[] png(int width, int height) throws java.io.IOException {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
  }

  private static byte[] jpeg(int width, int height) throws java.io.IOException {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
  }

  private static byte[] encode(BufferedImage image, String format) throws java.io.IOException {
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  private void rejected(UUID account, MockMultipartFile file, String reason) throws Exception {
    mvc.perform(authed(multipart(CV).file(file), account))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("FILE_REJECTED"))
        .andExpect(jsonPath("$.error.details[0].code").value(reason));
  }

  private int versionsOf(UUID account) {
    return jdbc.queryForObject(
        "select count(*) from candidate_cv_files f join candidate_profiles p"
            + " on p.id = f.candidate_profile_id where p.account_id = ?",
        Integer.class,
        account);
  }

  private UUID withCandidateProfile() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into accounts (id, is_candidate_enabled) values (?, true)", id);
    mvc.perform(
            authed(put(PROFILE), id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Ana López\",\"version\":0}"))
        .andExpect(status().isCreated());
    mvc.perform(
            authed(put(CANDIDATE), id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactEmail\":\"ana@example.test\",\"version\":0}"))
        .andExpect(status().isCreated());
    return id;
  }

  private static MockMultipartFile file(String name, byte[] content) {
    return new MockMultipartFile("file", name, "application/octet-stream", content);
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B authed(B request, UUID account) {
    String token =
        KEYS.token(ISSUER.issuerUri(), Instant.now()).subject(account.toString()).build();
    return request.header("Authorization", "Bearer " + token);
  }

  private void deliverEvents() {
    new com.usac.jobseeker.app.infrastructure.outbox.OutboxPoller(
            store,
            consumers,
            new com.usac.jobseeker.app.infrastructure.outbox.OutboxProperties(
                Duration.ofSeconds(1), 100, 3, Duration.ofMillis(1), Duration.ofMillis(5)),
            new org.springframework.transaction.support.TransactionTemplate(transactions),
            meters)
        .drain(50);
  }
}
