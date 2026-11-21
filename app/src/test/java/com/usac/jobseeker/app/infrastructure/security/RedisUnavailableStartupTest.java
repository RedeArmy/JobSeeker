package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usac.jobseeker.app.PostgresTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-0020, regla 9: el servicio no debe fallar al arrancar por Redis. Nadie escucha en el puerto
 * 1, así que Redis es inalcanzable desde el primer instante: el contexto debe arrancar, seguir vivo
 * y reintentar el oyente de sesiones en segundo plano, dejando constancia en una métrica.
 */
@SpringBootTest(properties = {"spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class RedisUnavailableStartupTest {

  @Autowired private MockMvc mvc;
  @Autowired private MeterRegistry meters;

  @Test
  void theApplicationStartsAndStaysAliveWithoutRedis() throws Exception {
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  void theFailureToSubscribeTheSessionListenerIsCountedAndNotFatal() {
    var failures = meters.find("jobseeker.redis.listener.start_failed").counter();
    assertThat(failures).isNotNull();
    assertThat(failures.count()).isGreaterThanOrEqualTo(1);
    assertThat(meters.find("jobseeker.redis.listener.subscribed").gauge().value()).isZero();
  }
}
