package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.nimbusds.jose.JWSAlgorithm;
import com.usac.jobseeker.app.infrastructure.security.api.ApiAuthProperties;
import com.usac.jobseeker.app.infrastructure.security.api.ClavarisJwtDecoder;
import com.usac.jobseeker.app.infrastructure.security.api.IdentityProviderUnavailableException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * El validador de los Bearer (ADR-0020, superficie B) con tokens RSA de verdad: lo que acepta, lo
 * que rechaza y qué hace cuando Clavaris no responde. Cada rechazo es una prueba separada porque
 * cada uno es una comprobación distinta que alguien podría quitar sin que nada más se rompa.
 */
class ClavarisJwtDecoderTest {

  private static final Duration SKEW = Duration.ofSeconds(30);
  private static final Duration RETRY = Duration.ofSeconds(10);

  private static TestTokens legitimate;
  private static TestTokens attacker;
  private static TestTokens rotated;

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-22T12:00:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private StubIssuer issuer;

  @BeforeAll
  static void generateKeys() {
    legitimate = TestTokens.withKey("kid-1");
    attacker = TestTokens.withKey("kid-1"); // el mismo `kid`, otra clave: la firma debe delatarla
    rotated = TestTokens.withKey("kid-2");
  }

  @BeforeEach
  void startIssuer() throws IOException {
    issuer = new StubIssuer();
    issuer.jwks = legitimate.jwks();
  }

  @AfterEach
  void stopIssuer() {
    issuer.close();
  }

  private ClavarisJwtDecoder decoder() {
    return decoder(List.of());
  }

  private ClavarisJwtDecoder decoder(List<String> allowedAudiences) {
    ClavarisProperties clavaris =
        new ClavarisProperties(
            issuer.issuerUri(),
            TestTokens.CLIENT_ID,
            "secreto",
            List.of("openid"),
            Duration.ofSeconds(2),
            Duration.ofMillis(500),
            RETRY);
    return new ClavarisJwtDecoder(
        new LazyClavarisClientRegistrationRepository(clavaris, clock),
        new ApiAuthProperties(allowedAudiences, List.of(), SKEW),
        clavaris,
        clock);
  }

  /**
   * Lo que lanza el decodificador ante un token. El decodificador se crea fuera de la lambda:
   * dentro debe haber una sola llamada que pueda fallar.
   */
  private Throwable rejected(String token) {
    ClavarisJwtDecoder decoder = decoder();
    return catchThrowable(() -> decoder.decode(token));
  }

  private TestTokens.Builder token() {
    return legitimate.token(issuer.issuerUri(), now.get());
  }

  // ---- lo que se acepta ----

  @Test
  void aWellFormedAccessTokenForThisApplicationIsAccepted() {
    UUID subject = UUID.randomUUID();
    String token = token().subject(subject.toString()).build();

    Jwt jwt = decoder().decode(token);

    assertThat(jwt.getSubject()).isEqualTo(subject.toString());
    assertThat(jwt.getAudience()).containsExactly(TestTokens.CLIENT_ID);
    assertThat(jwt.getIssuer()).hasToString(issuer.issuerUri());
  }

  @Test
  void anAudienceListThatIncludesOursAmongOthersIsAccepted() {
    String token = token().audience("otra-aplicacion", TestTokens.CLIENT_ID).build();

    assertThat(decoder().decode(token).getAudience()).contains(TestTokens.CLIENT_ID);
  }

  @Test
  void theCustomAudiencesReplaceTheDefaultInsteadOfAddingToIt() {
    String forThisApp = token().audience(TestTokens.CLIENT_ID).build();
    String forTheMobileApp = token().audience("app-movil").build();
    ClavarisJwtDecoder withMobile = decoder(List.of("app-movil", "app-web"));
    ClavarisJwtDecoder onlyMobile = decoder(List.of("app-movil"));

    assertThat(withMobile.decode(forTheMobileApp)).isNotNull();
    assertThat(onlyMobile.decode(forTheMobileApp)).isNotNull();
    assertThatThrownBy(() -> onlyMobile.decode(forThisApp)).isInstanceOf(BadJwtException.class);
    assertThatThrownBy(() -> withMobile.decode(forThisApp)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenCarryingScpInsteadOfScopeIsStillAnAccessToken() {
    // El validador acepta las dos formas habituales del claim; solo el ID token no trae ninguna.
    String token = token().noScope().build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  // ---- audiencia: el control que el decodificador por defecto no hace (G6) ----

  @Test
  void aTokenIssuedToAnotherApplicationOfTheSameOrganizationIsRejected() {
    String token = token().audience("otra-aplicacion-de-la-organizacion").build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenWithoutAudienceIsRejected() {
    String token = token().noAudience().build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void theAudienceIsComparedExactly() {
    for (String near :
        new String[] {
          "CLIENT-DE-PRUEBA",
          "client-de-prueba ",
          "client-de-prueba-2",
          "client-de",
          "client-de-prueba/"
        }) {
      String token = token().audience(near).build();

      assertThat(rejected(token)).as(near).isInstanceOf(BadJwtException.class);
    }
  }

  // ---- ID token: misma firma, mismo emisor y misma audiencia, pero no es un token de acceso ----

  @Test
  void anIdTokenIsNotAcceptedAsAnAccessTokenEvenThoughItIsSignedAndAddressedToUs() {
    String idToken = token().noScope().build();

    assertThat(rejected(idToken)).isInstanceOf(BadJwtException.class);
  }

  // ---- emisor ----

  @Test
  void aTokenFromAnotherIssuerIsRejected() {
    String token = token().issuer("https://otro-emisor.example/o/x").build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void anIssuerThatOnlyDiffersByATrailingSlashIsRejectedToo() {
    String token = token().issuer(issuer.issuerUri() + "/").build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  // ---- vigencia, con la tolerancia de 30 s ----

  @Test
  void anExpiredTokenIsRejectedOnlyOncePastTheClockSkew() {
    ClavarisJwtDecoder decoder = decoder();
    String insideSkew = token().expiresAt(now.get().minusSeconds(29)).build();
    String pastSkew = token().expiresAt(now.get().minusSeconds(31)).build();

    assertThat(decoder.decode(insideSkew)).isNotNull();
    assertThatThrownBy(() -> decoder.decode(pastSkew)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenNotYetValidIsRejectedOnlyBeyondTheClockSkew() {
    ClavarisJwtDecoder decoder = decoder();
    String insideSkew = token().notBefore(now.get().plusSeconds(29)).build();
    String beyondSkew = token().notBefore(now.get().plusSeconds(31)).build();

    assertThat(decoder.decode(insideSkew)).isNotNull();
    assertThatThrownBy(() -> decoder.decode(beyondSkew)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenThatWasValidBecomesInvalidAsTimePasses() {
    ClavarisJwtDecoder decoder = decoder();
    String token = token().build();
    assertThat(decoder.decode(token)).isNotNull();

    now.set(now.get().plusSeconds(300 + 31));

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
  }

  // ---- sub ----

  @ParameterizedTest
  @ValueSource(
      strings = {"no-es-un-uuid", "1-1-1-1-1", "auth0|abc", "persona@ejemplo.test", "", "   "})
  void aSubjectThatIsNotACanonicalUuidIsRejected(String subject) {
    String token = token().subject(subject).build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenWithoutSubjectIsRejected() {
    String token = token().noSubject().build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  // ---- firma y algoritmo ----

  @Test
  void aTokenSignedWithAnotherKeyUnderTheSameKeyIdIsRejected() {
    String forged = token().signedBy(attacker).build();

    assertThat(rejected(forged)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenWhosePayloadWasChangedAfterSigningIsRejected() {
    String original = UUID.randomUUID().toString();
    String tampered = token().subject(original).buildTampered(UUID.randomUUID().toString());

    assertThat(rejected(tampered)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenWithOneSignatureByteFlippedIsRejected() {
    String forged = token().buildWithOneSignatureByteFlipped();

    assertThat(rejected(forged)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void changingOnlyTheLastBase64CharacterOfTheSignatureLeavesTheSameBytesSoItIsNotAForgery() {
    // Documenta lo que se vio con Clavaris real: ese carácter solo trae bits de relleno. No es una
    // debilidad: la firma decodificada es idéntica y por eso sigue siendo válida.
    String original = token().build();
    String sameBytes = TestTokens.changeOnlyThePaddingBitsOfTheSignature(original);
    ClavarisJwtDecoder decoder = decoder();

    assertThat(sameBytes).isNotEqualTo(original);
    assertThat(decoder.decode(sameBytes)).isNotNull();
    assertThat(decoder.decode(original)).isNotNull();
  }

  @Test
  void anUnsignedTokenIsRejected() {
    String unsigned = token().buildUnsigned();

    assertThat(rejected(unsigned)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void theAlgorithmConfusionAttackIsRejected() {
    // HS256 firmado con la clave pública como secreto: válido si el servidor deja elegir el
    // algoritmo.
    String forged = token().buildWithHmacUsingThePublicKey();

    assertThat(rejected(forged)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void anotherRsaAlgorithmThanTheOneConfiguredIsRejectedEvenWithTheRightKey() {
    String token = token().algorithm(JWSAlgorithm.RS512).build();

    assertThat(rejected(token)).isInstanceOf(BadJwtException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "abc",
        "a.b",
        "a.b.c",
        "....",
        "eyJhbGciOiJSUzI1NiJ9.e30.",
        "Bearer abc.def.ghi"
      })
  void garbageIsRejectedAsABadTokenAndNeverAsAServerFailure(String garbage) {
    assertThat(rejected(garbage)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void anEnormousTokenIsRejectedWithoutFailingTheServer() {
    String huge = "a".repeat(200_000) + "." + "b".repeat(1_000) + "." + "c".repeat(1_000);

    assertThat(rejected(huge)).isInstanceOf(BadJwtException.class);
  }

  // ---- lo que se descarta sin necesitar a Clavaris ----

  @Test
  void whatCannotBeAnRs256JwtIsRejectedWithoutEvenAskingClavaris() {
    ClavarisJwtDecoder decoder = decoder();
    String[] rejectedBeforeAnyLookup = {
      "basura",
      token().buildUnsigned(),
      token().buildWithHmacUsingThePublicKey(),
      token().algorithm(JWSAlgorithm.RS512).build()
    };

    for (String token : rejectedBeforeAnyLookup) {
      assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    assertThat(issuer.discoveryRequests.get()).as("sin descubrimiento").isZero();
    assertThat(issuer.jwksRequests.get()).as("sin JWKS").isZero();
  }

  @Test
  void withClavarisDownGarbageIsStillABadTokenWhileAWellFormedOneIsAServiceFailure() {
    issuer.omittedFromDiscovery.add("jwks_uri");
    ClavarisJwtDecoder decoder = decoder();
    String wellFormed = token().build();

    assertThatThrownBy(() -> decoder.decode("basura")).isInstanceOf(BadJwtException.class);
    assertThatThrownBy(() -> decoder.decode(wellFormed))
        .isInstanceOf(IdentityProviderUnavailableException.class);
  }

  // ---- un descubrimiento incompleto: sin con qué comprobar, no es un token malo ----

  private ClavarisJwtDecoder decoderOverARegistrationWithout(String jwkSetUri, String issuerUri) {
    var builder =
        org.springframework.security.oauth2.client.registration.ClientRegistration
            .withRegistrationId("clavaris")
            .clientId(TestTokens.CLIENT_ID)
            .authorizationGrantType(
                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/cb")
            .authorizationUri("http://localhost/authorize")
            .tokenUri("http://localhost/token");
    if (jwkSetUri != null) {
      builder.jwkSetUri(jwkSetUri);
    }
    if (issuerUri != null) {
      builder.issuerUri(issuerUri);
    }
    var registration = builder.build();
    ClavarisProperties clavaris =
        new ClavarisProperties(
            "http://localhost/o/x",
            TestTokens.CLIENT_ID,
            "secreto",
            List.of("openid"),
            Duration.ofSeconds(2),
            Duration.ofMillis(500),
            RETRY);
    return new ClavarisJwtDecoder(
        id -> registration, new ApiAuthProperties(List.of(), List.of(), SKEW), clavaris, clock);
  }

  @Test
  void aDiscoveryWithoutTheKeySetAddressIsAServiceFailureAndNotABadToken() {
    ClavarisJwtDecoder decoder = decoderOverARegistrationWithout(null, "http://localhost/o/x");
    String token = token().build();

    assertThatThrownBy(() -> decoder.decode(token))
        .isInstanceOf(IdentityProviderUnavailableException.class)
        .hasMessageContaining("jwks_uri");
  }

  @Test
  void aDiscoveryWithoutTheIssuerIsAServiceFailureAndNotABadToken() {
    ClavarisJwtDecoder decoder = decoderOverARegistrationWithout("http://localhost/jwks", null);
    String token = token().build();

    assertThatThrownBy(() -> decoder.decode(token))
        .isInstanceOf(IdentityProviderUnavailableException.class)
        .hasMessageContaining("issuer");
  }

  // ---- claves: caché y rotación ----

  @Test
  void theKeySetIsFetchedOnceAndReusedForTheFollowingTokens() {
    ClavarisJwtDecoder decoder = decoder();

    decoder.decode(token().build());
    decoder.decode(token().build());
    decoder.decode(token().build());

    assertThat(issuer.jwksRequests.get()).isEqualTo(1);
  }

  @Test
  void aKeyThatClavarisRotatesInIsPickedUpWithoutRestartingJobSeeker() {
    ClavarisJwtDecoder decoder = decoder();
    decoder.decode(token().build());
    issuer.jwks = TestTokens.jwksOf(legitimate, rotated);
    String signedWithTheNewKey = rotated.token(issuer.issuerUri(), now.get()).build();

    assertThat(decoder.decode(signedWithTheNewKey)).isNotNull();
  }

  @Test
  void aKeyIdThatClavarisNeverPublishedIsRejectedAfterOneRefresh() {
    ClavarisJwtDecoder decoder = decoder();
    decoder.decode(token().build());
    int before = issuer.jwksRequests.get();
    String unknown = rotated.token(issuer.issuerUri(), now.get()).build();

    assertThatThrownBy(() -> decoder.decode(unknown)).isInstanceOf(BadJwtException.class);

    assertThat(issuer.jwksRequests.get() - before).as("recarga como mucho una vez").isEqualTo(1);
  }

  // ---- Clavaris no responde: no es culpa del token ----

  @Test
  void withClavarisNotYetDiscoveredTheFailureIsNotABadTokenAndRecoversByItself() {
    issuer.omittedFromDiscovery.add("jwks_uri");
    ClavarisJwtDecoder decoder = decoder();
    String token = token().build();

    assertThatThrownBy(() -> decoder.decode(token))
        .isInstanceOf(IdentityProviderUnavailableException.class)
        .isNotInstanceOf(BadJwtException.class);

    issuer.omittedFromDiscovery.clear();
    now.set(now.get().plus(RETRY));

    assertThat(decoder.decode(token().build())).isNotNull();
  }

  @Test
  void aKeySetThatCannotBeFetchedIsAServerSideFailureAndNotABadToken() {
    issuer.jwksStatus = 500;
    String token = token().build();

    Throwable failure = rejected(token);

    assertThat(failure).isInstanceOf(JwtException.class).isNotInstanceOf(BadJwtException.class);
  }

  @Test
  void aKeySetThatNeverAnswersDoesNotHangTheRequest() {
    // Un minuto de retraso frente a 500 ms de tiempo de lectura: sin tiempo de espera colgaría.
    issuer.jwksDelayMillis = 60_000;
    String token = token().build();
    ClavarisJwtDecoder decoder = decoder();

    Throwable failure =
        assertTimeoutPreemptively(
            Duration.ofSeconds(20), () -> catchThrowable(() -> decoder.decode(token)));

    assertThat(failure).isInstanceOf(JwtException.class).isNotInstanceOf(BadJwtException.class);
  }

  @Test
  void whenTheKeySetComesBackTheNextTokenIsAcceptedWithoutRestarting() {
    issuer.jwksStatus = 500;
    ClavarisJwtDecoder decoder = decoder();
    String token = token().build();
    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);

    issuer.jwksStatus = 200;

    assertThat(decoder.decode(token)).isNotNull();
  }

  @Test
  void manyThreadsDecodingForTheFirstTimeShareOneDecoderAndOneKeySetRequest() throws Exception {
    // Bajo concurrencia el primer uso descubre a Clavaris y pide las claves una sola vez. Que se
    // construya un decodificador de más no se nota desde fuera (la caché de claves se comparte),
    // así
    // que el segundo chequeo del bloqueo es un mutante equivalente que PIT deja vivo.
    ClavarisJwtDecoder decoder = decoder();
    String token = token().build();
    int threads = 16;
    java.util.concurrent.CountDownLatch startTogether = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    try {
      java.util.List<java.util.concurrent.Future<Jwt>> results = new java.util.ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  return decoder.decode(token);
                }));
      }
      startTogether.countDown();
      for (java.util.concurrent.Future<Jwt> result : results) {
        assertThat(result.get(30, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(issuer.discoveryRequests.get()).as("un solo descubrimiento").isEqualTo(1);
    assertThat(issuer.jwksRequests.get()).as("una sola petición de claves").isEqualTo(1);
  }
}
