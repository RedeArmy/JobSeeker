package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.app.infrastructure.security.api.ApiAvailabilityFilter;
import com.usac.jobseeker.app.infrastructure.security.api.ApiConfiguration;
import com.usac.jobseeker.app.infrastructure.security.api.BearerAuthenticationEntryPoint;
import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessCoordinator;
import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessFilter;
import com.usac.jobseeker.app.infrastructure.security.liveness.LivenessStore;
import com.usac.jobseeker.app.infrastructure.security.liveness.RefreshTokenClient;
import com.usac.jobseeker.app.infrastructure.security.liveness.RevokingLogoutHandler;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Las tres cadenas de seguridad del único desplegable (ADR-0020), de más a menos específica:
 *
 * <ol>
 *   <li>`/api/v1/webhooks/clavaris`: sin sesión ni Bearer; la autenticidad la dará solo la firma
 *       (sub-paso 4.5). Hasta entonces rechaza todo.
 *   <li>`/api/v1/**`: API sin estado con Bearer de Clavaris (sub-paso 4.4).
 *   <li>El resto: web con sesión en Redis y CSRF tras un login OIDC con Clavaris.
 * </ol>
 *
 * La cadena C nace cerrada: ninguna ruta queda abierta por omisión mientras no se implemente. Todo
 * lo no declarado en la cadena web también se rechaza, y toda ruta de la API exige un Bearer
 * válido.
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

  /**
   * Superficie B (ADR-0020): la API JSON con Bearer de Clavaris. Sin estado, sin cookie y sin CSRF;
   * la identidad sale del token (firma, emisor, vigencia, audiencia y tipo) y la autoridad, de la
   * base local en cada petición, nunca de los claims.
   */
  @Bean
  @Order(2)
  SecurityFilterChain apiChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      @Qualifier(ApiConfiguration.CORS_SOURCE) CorsConfigurationSource cors,
      ProvisionAccountUseCase provisionAccount,
      ResolveActorUseCase resolveActor,
      ClavarisProperties clavaris)
      throws Exception {
    // Los Bearer no traen autoridades de negocio: las capacidades las decide la cuenta local
    // (NFR-SEC-03, BR-ACCT-04), así que no se convierten los `scope` en roles.
    JwtAuthenticationConverter noAuthorities = new JwtAuthenticationConverter();
    noAuthorities.setJwtGrantedAuthoritiesConverter(jwt -> List.of());

    http.securityMatcher("/api/v1/**")
        // S4502: sin cookie de sesión ni otra credencial ambiental que un sitio ajeno pueda
        // reutilizar, no hay nada que proteger de CSRF.
        .csrf(csrf -> csrf.disable()) // NOSONAR
        .cors(c -> c.configurationSource(cors))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(
            resource ->
                resource
                    .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(noAuthorities))
                    .authenticationEntryPoint(new BearerAuthenticationEntryPoint())
                    .accessDeniedHandler(accessDenied()))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(new BearerAuthenticationEntryPoint())
                    .accessDeniedHandler(accessDenied()))
        .addFilterBefore(
            new ApiAvailabilityFilter(clavaris.retryAfterFailure()),
            BearerTokenAuthenticationFilter.class)
        .addFilterAfter(
            new ActorFilter(
                resolveActor, provisionAccount, JwtAuthenticationToken.class::isInstance, Set.of()),
            BearerTokenAuthenticationFilter.class);
    return http.build();
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
      Clock clock,
      OAuth2AuthorizedClientRepository authorizedClients,
      LivenessCoordinator liveness,
      LivenessStore livenessStore,
      RefreshTokenClient refreshTokens) {
    // PKCE (S256) explícito: no se depende de un valor por defecto de la biblioteca (ADR-0020,
    // regla 10).
    DefaultOAuth2AuthorizationRequestResolver authorizationRequests =
        new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    authorizationRequests.setAuthorizationRequestCustomizer(
        OAuth2AuthorizationRequestCustomizers.withPkce());

    // Cierre: primero se revoca el refresh token (necesita la sesión viva) y luego se redirige al
    // fin de sesión de Clavaris; sin ese endpoint, o con Clavaris sin resolver, vuelve a `/`.
    OidcClientInitiatedLogoutSuccessHandler logoutSuccess =
        new OidcClientInitiatedLogoutSuccessHandler(clients);
    logoutSuccess.setDefaultTargetUrl("/");

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
                    // El refresh token va cifrado a Redis, no en claro en la sesión (regla 3).
                    .authorizedClientRepository(authorizedClients)
                    .successHandler(new AccountProvisioningSuccessHandler(provisionAccount)))
        .logout(
            logout ->
                logout
                    .logoutUrl("/app/logout")
                    .addLogoutHandler(new RevokingLogoutHandler(livenessStore, refreshTokens))
                    .logoutSuccessHandler(logoutSuccess))
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
        // Antes del filtro anónimo: si la sesión ya no la respalda Clavaris se limpia el contexto y
        // la petición sigue como anónima, hacia el login.
        .addFilterBefore(
            new LivenessFilter(liveness, livenessStore), AnonymousAuthenticationFilter.class)
        .addFilterAfter(
            new ActorFilter(
                resolveActor,
                provisionAccount,
                OAuth2AuthenticationToken.class::isInstance,
                Set.of("/app/logout")),
            AnonymousAuthenticationFilter.class);
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
