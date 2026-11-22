package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * El identificador de correlación (ADR-0012): lo genera siempre el servidor, llega a los logs, a la
 * respuesta y al sobre de error, y no se filtra de una petición a la siguiente.
 */
class TraceIdFilterTest {

  private final TraceIdFilter filter = new TraceIdFilter();

  @AfterEach
  void cleanMdc() {
    MDC.clear();
  }

  @Test
  void everyRequestGetsAFreshUuidInTheHeaderAndTheRequestAttribute() throws Exception {
    MockHttpServletRequest first = new MockHttpServletRequest();
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    MockHttpServletRequest second = new MockHttpServletRequest();
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();

    filter.doFilter(first, firstResponse, new MockFilterChain());
    filter.doFilter(second, secondResponse, new MockFilterChain());

    String firstId = firstResponse.getHeader(TraceIdFilter.HEADER);
    assertThat(UUID.fromString(firstId)).isNotNull();
    assertThat(TraceIdFilter.traceId(first)).isEqualTo(firstId);
    assertThat(secondResponse.getHeader(TraceIdFilter.HEADER)).isNotEqualTo(firstId);
  }

  @Test
  void aTraceIdSentByTheClientIsIgnoredBecauseItCouldInjectTextIntoTheLogs() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TraceIdFilter.HEADER, "forjado\nlevel=ERROR admin logged in");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader(TraceIdFilter.HEADER)).doesNotContain("forjado");
    assertThat(TraceIdFilter.traceId(request)).doesNotContain("forjado");
  }

  @Test
  void theIdIsInTheLogContextWhileTheRequestRunsAndGoneAfterwards() throws Exception {
    List<String> seenInside = new ArrayList<>();
    MockHttpServletRequest request = new MockHttpServletRequest();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            seenInside.add(MDC.get(TraceIdFilter.MDC_KEY));
          }
        });

    assertThat(seenInside).containsExactly(TraceIdFilter.traceId(request));
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void theLogContextIsClearedEvenWhenTheRestOfTheChainFails() {
    MockFilterChain failing =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
              throws ServletException {
            throw new ServletException("falla de otro filtro");
          }
        };

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
        .isInstanceOf(ServletException.class);

    assertThat(MDC.get(TraceIdFilter.MDC_KEY))
        .as("no debe quedar en el hilo, que se reutiliza")
        .isNull();
  }

  @Test
  void aRequestThatNeverPassedThroughTheFilterHasAnEmptyTraceId() {
    assertThat(TraceIdFilter.traceId(new MockHttpServletRequest())).isEmpty();
  }
}
