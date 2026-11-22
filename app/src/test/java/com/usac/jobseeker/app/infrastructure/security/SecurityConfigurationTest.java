package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/** Piezas pequeñas de la configuración de seguridad que no dependen de Spring para probarse. */
class SecurityConfigurationTest {

  private final SecurityConfiguration configuration = new SecurityConfiguration();

  @Test
  void theApplicationClockIsUtcSoTimeoutsDoNotDependOnTheServerTimeZone() {
    Clock clock = configuration.clock();

    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void theTraceIdFilterRunsBeforeAnyOtherFilterSoEvenSecurityResponsesCarryIt() {
    assertThat(configuration.traceIdFilter().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(configuration.traceIdFilter().getFilter()).isInstanceOf(TraceIdFilter.class);
  }

  @Test
  void theContentSecurityPolicyAllowsNothingInlineAndNothingFromOtherOrigins() {
    String csp = SecurityConfiguration.WebHeaders.CSP;

    assertThat(csp)
        .contains("default-src 'self'")
        .contains("script-src 'self'")
        .contains("style-src 'self'")
        .contains("object-src 'none'")
        .contains("base-uri 'self'")
        .contains("form-action 'self'")
        .contains("frame-ancestors 'none'")
        .doesNotContain("unsafe-inline")
        .doesNotContain("unsafe-eval")
        .doesNotContain("http:")
        .doesNotContain("*");
  }
}
