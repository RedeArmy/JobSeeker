package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de las pruebas que solo necesitan un Clavaris falso (TD-017). Spring reutiliza un contexto
 * cuando su configuración es la misma, pero un `@DynamicPropertySource` declarado en cada clase es
 * una clave distinta aunque registre lo mismo: cada prueba arrancaba su propio contexto (unos 10
 * s). Con el emisor, las claves y las propiedades declarados una sola vez aquí, todas las subclases
 * comparten uno.
 *
 * <p>Una subclase que declare propiedades propias vuelve a tener su contexto: lo que se añada aquí
 * debe ser inocuo para todas (por eso los operadores y los topes de publicación, que solo notan las
 * pruebas que los usan).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureTestConfiguration.class)
abstract class SharedIssuerIntegrationTest {

  /** Un solo emisor por JVM: se cierra con ella, porque lo usan contextos que viven en caché. */
  static final StubIssuer ISSUER = startIssuer();

  static final TestTokens KEYS = TestTokens.withKey("kid-shared");

  /** La cuenta operadora de la plataforma en estas pruebas (`jobseeker.auth.operators`). */
  static final UUID OPERATOR = UUID.fromString("0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e");

  private static StubIssuer startIssuer() {
    try {
      StubIssuer issuer = new StubIssuer();
      Runtime.getRuntime().addShutdownHook(new Thread(issuer::close));
      return issuer;
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void sharedIssuerProperties(DynamicPropertyRegistry registry) {
    registry.add("jobseeker.auth.clavaris.issuer-uri", ISSUER::issuerUri);
    registry.add("jobseeker.auth.clavaris.client-id", () -> TestTokens.CLIENT_ID);
    registry.add("jobseeker.auth.clavaris.client-secret", () -> "secreto-de-prueba");
    registry.add("jobseeker.auth.operators.account-ids", OPERATOR::toString);
    // Topes pequeños y distintos para que el de una cuenta verificada se alcance en la prueba de
    // publicar; las demás no publican vacantes.
    registry.add("jobseeker.job.publishing.unverified-max-published", () -> "2");
    registry.add("jobseeker.job.publishing.verified-max-published", () -> "3");
  }

  // El emisor es compartido: cada prueba parte de las claves publicadas y de un JWKS sano, deje lo
  // que deje la anterior.
  @BeforeEach
  void publishTheSharedKeys() {
    ISSUER.jwks = KEYS.jwks();
    ISSUER.jwksStatus = 200;
  }
}
