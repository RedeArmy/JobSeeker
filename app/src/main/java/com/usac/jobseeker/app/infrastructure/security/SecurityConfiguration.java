package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Las tres cadenas de seguridad del único desplegable (ADR-0020), de más a menos específica:
 *
 * <ol>
 *   <li>`/api/v1/webhooks/clavaris`: sin sesión ni Bearer; la autenticidad la dará solo la firma
 *       (sub-paso 4.5). Hasta entonces rechaza todo.
 *   <li>`/api/v1/**`: API sin estado con Bearer (sub-paso 4.4). Hasta entonces rechaza todo.
 *   <li>El resto: web con sesión en Redis y CSRF tras un login OIDC con Clavaris.
 * </ol>
 *
 * Las cadenas B y C nacen cerradas: ninguna ruta queda abierta por omisión mientras no se
 * implementen. Todo lo no declarado en la cadena web también se rechaza.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ClavarisProperties.class, SessionProperties.class})
class SecurityConfiguration {

  private static final String LOGIN_PATH =
      "/oauth2/authorization/" + LazyClavarisClientRegistrationRepository.REGISTRATION_ID;

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  ClientRegistrationRepository clientRegistrationRepository(
      ClavarisProperties clavaris, Clock clock) {
    return new LazyClavarisClientRegistrationRepository(clavaris, clock);
  }

  // Antes que Spring Security (orden -100): el traceId debe existir también para sus respuestas.
  @Bean
  FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
    FilterRegistrationBean<TraceIdFilter> registration =
        new FilterRegistrationBean<>(new TraceIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  @Order(1)
  SecurityFilterChain webhookChain(HttpSecurity http) {
    return closedStatelessChain(http, "/api/v1/webhooks/clavaris");
  }

  @Bean
  @Order(2)
  SecurityFilterChain apiChain(HttpSecurity http) {
    return closedStatelessChain(http, "/api/v1/**");
  }

  private static SecurityFilterChain closedStatelessChain(HttpSecurity http, String pattern) {
    http.securityMatcher(pattern)
        // S4502: la cadena es sin estado y solo acepta Bearer o firma, sin cookie de sesión ni otra
        // credencial ambiental que un sitio ajeno pueda reutilizar: no hay nada que proteger de
        // CSRF.
        .csrf(csrf -> csrf.disable()) // NOSONAR
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().denyAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(bearerEntryPoint()).accessDeniedHandler(accessDenied()));
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain webChain(
      HttpSecurity http,
      ClientRegistrationRepository clients,
      ProvisionAccountUseCase provisionAccount,
      ResolveActorUseCase resolveActor,
      SessionProperties session,
      ClavarisProperties clavaris,
      Clock clock) {
    // PKCE (S256) explícito: no se depende de un valor por defecto de la biblioteca (ADR-0020,
    // regla 10).
    DefaultOAuth2AuthorizationRequestResolver authorizationRequests =
        new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    authorizationRequests.setAuthorizationRequestCustomizer(
        OAuth2AuthorizationRequestCustomizers.withPkce());

    http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/css/**", "/js/**", "/favicon.ico", "/error")
                    .permitAll()
                    .requestMatchers("/")
                    .permitAll()
                    .requestMatchers("/app/**")
                    .authenticated()
                    // Lo no declarado se rechaza: una ruta nueva exige decidir su acceso.
                    .anyRequest()
                    .denyAll())
        .oauth2Login(
            login ->
                login
                    .authorizationEndpoint(
                        e -> e.authorizationRequestResolver(authorizationRequests))
                    .successHandler(new AccountProvisioningSuccessHandler(provisionAccount)))
        .logout(logout -> logout.logoutUrl("/app/logout").logoutSuccessUrl("/"))
        .csrf(Customizer.withDefaults())
        .headers(WebHeaders::apply)
        .exceptionHandling(
            e -> e.authenticationEntryPoint(webEntryPoint()).accessDeniedHandler(accessDenied()))
        .addFilterBefore(
            new AbsoluteSessionTimeoutFilter(session.absoluteTimeout(), clock),
            SecurityContextHolderFilter.class)
        // Antes del redirect a Clavaris: sin registro (Clavaris caído o sin configurar) responde
        // 503 y no 500.
        .addFilterBefore(
            new IdentityProviderAvailabilityFilter(clients, clavaris.retryAfterFailure()),
            OAuth2AuthorizationRequestRedirectFilter.class)
        .addFilterAfter(
            new ActorFilter(resolveActor, provisionAccount), AnonymousAuthenticationFilter.class);
    return http.build();
  }

  /**
   * Navegación: redirige al login. HTMX: 401 con `HX-Redirect`, para no incrustar el login en un
   * fragmento.
   */
  private static AuthenticationEntryPoint webEntryPoint() {
    LoginUrlAuthenticationEntryPoint redirect = new LoginUrlAuthenticationEntryPoint(LOGIN_PATH);
    return (request, response, exception) -> {
      if (isHtmx(request)) {
        response.setHeader("HX-Redirect", LOGIN_PATH);
        ErrorEnvelope.write(
            request,
            response,
            HttpStatus.UNAUTHORIZED.value(),
            "AUTHENTICATION_REQUIRED",
            "Inicia sesión para continuar.");
      } else {
        redirect.commence(request, response, exception);
      }
    };
  }

  private static boolean isHtmx(HttpServletRequest request) {
    return Boolean.parseBoolean(request.getHeader("HX-Request"));
  }

  private static AuthenticationEntryPoint bearerEntryPoint() {
    return (request, response, exception) -> {
      response.setHeader("WWW-Authenticate", "Bearer");
      ErrorEnvelope.write(
          request,
          response,
          HttpStatus.UNAUTHORIZED.value(),
          "AUTHENTICATION_REQUIRED",
          "Se requiere autenticación.");
    };
  }

  private static AccessDeniedHandler accessDenied() {
    return (request, response, exception) -> {
      if (exception instanceof CsrfException) {
        ErrorEnvelope.write(
            request,
            response,
            HttpStatus.FORBIDDEN.value(),
            "CSRF_TOKEN_INVALID",
            "El token CSRF falta o no es válido.");
      } else {
        ErrorEnvelope.write(
            request,
            response,
            HttpStatus.FORBIDDEN.value(),
            "ACCESS_DENIED",
            "No tienes permiso para esta operación.");
      }
    };
  }

  /** Cabeceras de seguridad de la cadena web (ADR-0020). */
  static final class WebHeaders {

    // CSP estricta sin `unsafe-inline`: HTMX se sirve desde /js y sin `eval`, y no hay estilos ni
    // scripts en línea.
    static final String CSP =
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    private WebHeaders() {}

    static void apply(HeadersConfigurer<HttpSecurity> headers) {
      headers
          .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
          .httpStrictTransportSecurity(
              hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
          .referrerPolicy(
              referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
          .frameOptions(frame -> frame.deny())
          .permissionsPolicyHeader(
              policy -> policy.policy("camera=(), microphone=(), geolocation=()"));
    }
  }
}
