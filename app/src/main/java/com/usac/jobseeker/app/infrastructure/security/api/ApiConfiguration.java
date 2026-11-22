package com.usac.jobseeker.app.infrastructure.security.api;

import com.usac.jobseeker.app.infrastructure.security.ClavarisProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Piezas de la autenticación Bearer de la API (ADR-0020, superficie B). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiAuthProperties.class)
public class ApiConfiguration {

  public static final String CORS_SOURCE = "apiCorsConfigurationSource";

  @Bean
  JwtDecoder clavarisJwtDecoder(
      ClientRegistrationRepository clients,
      ApiAuthProperties api,
      ClavarisProperties clavaris,
      Clock clock) {
    return new ClavarisJwtDecoder(clients, api, clavaris, clock);
  }

  /**
   * CORS cerrado por defecto: con la lista de orígenes vacía (el MVP) ninguna petición de otro
   * origen se acepta. Los Bearer van en una cabecera que el navegador no adjunta solo, así que no
   * se permiten credenciales.
   */
  @Bean(CORS_SOURCE)
  CorsConfigurationSource apiCorsConfigurationSource(ApiAuthProperties api) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(api.allowedOrigins());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cors.setExposedHeaders(List.of("X-Trace-Id", "Retry-After"));
    cors.setAllowCredentials(false);
    cors.setMaxAge(Duration.ofMinutes(10));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/v1/**", cors);
    return source;
  }
}
