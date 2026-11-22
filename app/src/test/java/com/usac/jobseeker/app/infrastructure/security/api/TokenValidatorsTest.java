package com.usac.jobseeker.app.infrastructure.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Los tres validadores propios de los Bearer (ADR-0020, G6), cada uno aislado y sobre un `Jwt`
 * armado a mano: qué aceptan, qué rechazan y que su error siempre es `invalid_token`.
 */
class TokenValidatorsTest {

  private static Jwt jwt(Consumer<Jwt.Builder> customize) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuedAt(Instant.parse("2026-11-22T12:00:00Z"))
            .expiresAt(Instant.parse("2026-11-22T12:05:00Z"));
    customize.accept(builder);
    return builder.build();
  }

  private static void assertInvalidToken(OAuth2TokenValidatorResult result) {
    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors())
        .singleElement()
        .extracting(OAuth2Error::getErrorCode)
        .isEqualTo("invalid_token");
  }

  // ---- AudienceValidator ----

  @Test
  void anAudienceOnTheAllowedListIsAccepted() {
    AudienceValidator validator = new AudienceValidator(Set.of("jobseeker", "app-movil"));

    assertThat(validator.validate(jwt(b -> b.audience(List.of("app-movil")))).hasErrors())
        .isFalse();
  }

  @Test
  void anyOneOfSeveralAudiencesOnTheTokenIsEnough() {
    AudienceValidator validator = new AudienceValidator(Set.of("jobseeker"));

    assertThat(
            validator
                .validate(jwt(b -> b.audience(List.of("otra", "jobseeker", "mas"))))
                .hasErrors())
        .isFalse();
  }

  @Test
  void anAudienceNotOnTheListIsRejectedAsAnInvalidToken() {
    AudienceValidator validator = new AudienceValidator(Set.of("jobseeker"));

    assertInvalidToken(validator.validate(jwt(b -> b.audience(List.of("otra-aplicacion")))));
  }

  @Test
  void aTokenWithoutAudienceIsRejected() {
    AudienceValidator validator = new AudienceValidator(Set.of("jobseeker"));

    assertInvalidToken(validator.validate(jwt(b -> b.subject("x"))));
  }

  @Test
  void withNoAllowedAudiencesEverythingIsRejectedSoAMisconfigurationNeverOpensTheApi() {
    AudienceValidator validator = new AudienceValidator(List.of());

    assertInvalidToken(validator.validate(jwt(b -> b.audience(List.of("jobseeker")))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"JOBSEEKER", "jobseeker ", " jobseeker", "jobseeke", "jobseeker2", "*"})
  void theComparisonIsExactWithNoCaseFoldingTrimmingPrefixesOrWildcards(String audience) {
    AudienceValidator validator = new AudienceValidator(Set.of("jobseeker"));

    assertInvalidToken(validator.validate(jwt(b -> b.audience(List.of(audience)))));
  }

  @Test
  void theRejectionDoesNotRevealWhichAudiencesAreAccepted() {
    AudienceValidator validator = new AudienceValidator(Set.of("secreto-interno"));

    OAuth2TokenValidatorResult result = validator.validate(jwt(b -> b.audience(List.of("x"))));

    assertThat(result.getErrors().iterator().next().getDescription())
        .doesNotContain("secreto-interno");
  }

  // ---- SubjectValidator ----

  @Test
  void aCanonicalUuidSubjectIsAccepted() {
    assertThat(
            new SubjectValidator()
                .validate(jwt(b -> b.subject(UUID.randomUUID().toString()).claim("x", 1)))
                .hasErrors())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"no-es-uuid", "1-1-1-1-1", "0-0-0-0-0", "auth0|abc", "persona@x.test", ""})
  void aSubjectThatIsNotACanonicalUuidIsRejected(String subject) {
    assertInvalidToken(new SubjectValidator().validate(jwt(b -> b.subject(subject).claim("x", 1))));
  }

  @Test
  void aTokenWithoutSubjectIsRejected() {
    assertInvalidToken(new SubjectValidator().validate(jwt(b -> b.claim("x", 1))));
  }

  // ---- AccessTokenValidator ----

  @Test
  void aTokenWithScopeIsAnAccessToken() {
    assertThat(
            new AccessTokenValidator()
                .validate(jwt(b -> b.claim("scope", List.of("openid"))))
                .hasErrors())
        .isFalse();
  }

  @Test
  void aTokenWithScpIsAlsoAnAccessToken() {
    assertThat(
            new AccessTokenValidator()
                .validate(jwt(b -> b.claim("scp", List.of("openid"))))
                .hasErrors())
        .isFalse();
  }

  @Test
  void aTokenWithoutAnyScopeIsAnIdTokenAndIsRejected() {
    assertInvalidToken(
        new AccessTokenValidator()
            .validate(jwt(b -> b.claim("nonce", "n").claim("sid", "s").claim("azp", "c"))));
  }

  @Test
  void everyValidatorDescribesTheErrorInAsciiBecauseItEndsUpInAnHttpHeader() {
    Jwt empty = jwt(b -> b.claim("x", 1));

    for (OAuth2TokenValidatorResult result :
        List.of(
            new AudienceValidator(Set.of("a")).validate(empty),
            new SubjectValidator().validate(empty),
            new AccessTokenValidator().validate(empty))) {
      assertThat(result.getErrors().iterator().next().getDescription()).matches("\\p{ASCII}+");
    }
  }
}
