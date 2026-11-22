package com.usac.jobseeker.app.infrastructure.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.app.infrastructure.security.ClavarisProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** La configuración de los Bearer: qué audiencias valen, qué orígenes CORS y cuánta tolerancia. */
class ApiAuthPropertiesTest {

  private static ClavarisProperties clavaris(String clientId) {
    return new ClavarisProperties(
        "http://clavaris/o/x",
        clientId,
        "secreto",
        List.of("openid"),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10));
  }

  private static ApiAuthProperties with(
      List<String> audiences, List<String> origins, Duration skew) {
    return new ApiAuthProperties(audiences, origins, skew);
  }

  @Test
  void withoutConfiguredAudiencesOnlyTheClientIdOfJobSeekerIsAccepted() {
    assertThat(
            with(List.of(), List.of(), Duration.ofSeconds(30))
                .effectiveAudiences(clavaris("jobseeker")))
        .containsExactly("jobseeker");
  }

  @Test
  void configuredAudiencesReplaceTheClientIdInsteadOfAddingToIt() {
    ApiAuthProperties properties =
        with(List.of("app-movil", "app-web"), List.of(), Duration.ofSeconds(30));

    assertThat(properties.effectiveAudiences(clavaris("jobseeker")))
        .containsExactly("app-movil", "app-web");
  }

  @Test
  void withNeitherAudiencesNorClientIdNothingIsAcceptedSoItFailsClosed() {
    for (String clientId : new String[] {null, "", "   "}) {
      assertThat(
              with(List.of(), List.of(), Duration.ofSeconds(30))
                  .effectiveAudiences(clavaris(clientId)))
          .as("client id: '%s'", clientId)
          .isEmpty();
    }
  }

  @Test
  void blankEntriesAndDuplicatesAreDroppedAndTheRestIsTrimmed() {
    ApiAuthProperties properties =
        with(
            List.of(" app-movil ", "", "  ", "app-movil", "app-web"),
            List.of(" https://app.ejemplo.test ", "", "https://app.ejemplo.test"),
            Duration.ofSeconds(30));

    assertThat(properties.allowedAudiences()).containsExactly("app-movil", "app-web");
    assertThat(properties.allowedOrigins()).containsExactly("https://app.ejemplo.test");
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "https://*.ejemplo.test", "https://app.*"})
  void aWildcardOriginIsRefusedBecauseCorsMustStayClosedOrExplicit(String origin) {
    List<String> origins = List.of(origin);
    Duration skew = Duration.ofSeconds(30);

    assertThatThrownBy(() -> with(List.of(), origins, skew))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("comodines");
  }

  @Test
  void theClockSkewMustBeBetweenZeroAndTwoMinutes() {
    assertThat(with(List.of(), List.of(), Duration.ZERO).clockSkew()).isEqualTo(Duration.ZERO);
    assertThat(with(List.of(), List.of(), Duration.ofMinutes(2)).clockSkew())
        .isEqualTo(Duration.ofMinutes(2));

    assertThatThrownBy(() -> with(List.of(), List.of(), Duration.ofSeconds(121)))
        .hasMessageContaining("entre 0 y 2 minutos");
    assertThatThrownBy(() -> with(List.of(), List.of(), Duration.ofSeconds(-1)))
        .hasMessageContaining("entre 0 y 2 minutos");
  }

  @Test
  void theConfiguredListsCannotBeChangedAfterwards() {
    ApiAuthProperties properties = with(List.of("a"), List.of("https://o.test"), Duration.ZERO);
    List<String> audiences = properties.allowedAudiences();
    List<String> origins = properties.allowedOrigins();

    assertThatThrownBy(() -> audiences.add("b")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> origins.add("https://x.test"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
