package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.usecase.getmycv.CvFileView;
import com.usac.jobseeker.cv.application.usecase.getmycv.MyCvView;
import com.usac.jobseeker.cv.application.usecase.uploadcv.UploadCvCommand;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ResponseStatus;

/** La pantalla del CV solo traduce: la sesión al caso de uso y el resultado a HTML. */
class CvScreenControllerTest {

  private static final byte[] PDF = "%PDF-1.7".getBytes();

  private final UUID caller = UUID.randomUUID();
  private final CvFileView version =
      new CvFileView(
          UUID.randomUUID(), "Mi CV.pdf", "PDF", 1536, Instant.parse("2026-11-26T23:30:00Z"), true);
  private final List<Object> calls = new ArrayList<>();
  private boolean hasProfile = true;

  private final CvScreenController controller =
      new CvScreenController(
          command -> {
            calls.add(command);
            return version;
          },
          query -> {
            if (!hasProfile) {
              throw new NotFoundException();
            }
            return new MyCvView(Optional.of(version), List.of(version), 20);
          },
          query -> {
            calls.add(query);
            return URI.create("https://almacen.test/cv?firma=x");
          });

  private Principal who() {
    return caller::toString;
  }

  @Test
  void thePageListsTheVersionsReadyToShow() {
    var model = new ConcurrentModel();

    assertThat(controller.page(who(), model)).isEqualTo("cv/candidate-cv");

    assertThat(model.getAttribute("hasProfile")).isEqualTo(true);
    assertThat(model.getAttribute("maxVersions")).isEqualTo(20);
    var row = (CvScreenController.CvRow) model.getAttribute("current");
    assertThat(row.size()).isEqualTo("1.5 KB");
    assertThat(row.date()).isEqualTo("2026-11-26");
    assertThat(row.name()).isEqualTo("Mi CV.pdf");
    assertThat(model.getAttribute("versions")).asList().containsExactly(row);
  }

  @Test
  void withoutACandidateProfileThePageAsksToCreateIt() {
    hasProfile = false;
    var model = new ConcurrentModel();

    controller.page(who(), model);

    assertThat(model.getAttribute("hasProfile")).isEqualTo(false);
    assertThat(model.containsAttribute("current")).isFalse();
  }

  @Test
  void uploadingSendsTheSessionAccountTheNameAndTheBytes() {
    var model = new ConcurrentModel();

    String view = controller.upload(who(), model, WebFakes.file("Mi CV.pdf", PDF));

    assertThat(view).isEqualTo("cv/candidate-cv :: content");
    assertThat(model.getAttribute("notice")).asString().contains("versión vigente");
    assertThat(calls)
        .singleElement()
        .isInstanceOfSatisfying(
            UploadCvCommand.class,
            c -> {
              assertThat(c.accountId().value()).isEqualTo(caller);
              assertThat(c.originalName()).isEqualTo("Mi CV.pdf");
              assertThat(c.content()).isEqualTo(PDF);
            });
  }

  @Test
  void noFileIsAnErrorBeforeReachingTheUseCase() {
    var none = new ConcurrentModel();
    var empty = new ConcurrentModel();

    assertThat(controller.upload(who(), none, null)).isEqualTo("cv/candidate-cv :: errors");
    assertThat(controller.upload(who(), empty, WebFakes.file("x.pdf", new byte[0])))
        .isEqualTo("cv/candidate-cv :: errors");
    assertThat(none.getAttribute("errors")).asList().singleElement().asString().contains("Elige");
    assertThat(calls).isEmpty();
  }

  @Test
  void aFileThatCannotBeReadFailsWithoutReachingTheUseCase() {
    var model = new ConcurrentModel();
    Principal principal = who();
    var broken = WebFakes.file("x.pdf", PDF, true);

    assertThatThrownBy(() -> controller.upload(principal, model, broken))
        .isInstanceOf(UncheckedIOException.class);
    assertThat(calls).isEmpty();
  }

  // Una navegación: 302 a la URL firmada, sin que el navegador la guarde.
  @Test
  void downloadingRedirectsToTheSignedUrlWithoutCaching() {
    var response = controller.download(who(), version.id().toString());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).hasHost("almacen.test");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  @Test
  void aMalformedIdIsNotFound() {
    Principal principal = who();

    assertThatThrownBy(() -> controller.download(principal, "no-es-un-uuid"))
        .isInstanceOf(NotFoundException.class);
    assertThat(calls).isEmpty();
  }

  // ---- manejadores de error ----

  @Test
  void aRejectionOrAnUnavailableScannerAnswersTheErrorZoneInSpanish() {
    var rejected = new ConcurrentModel();
    var unavailable = new ConcurrentModel();

    assertThat(controller.rejected(new FileRejectedException(Reason.TOO_LARGE), rejected))
        .isEqualTo("cv/candidate-cv :: errors");
    assertThat(controller.unavailable(unavailable)).isEqualTo("cv/candidate-cv :: errors");

    assertThat(rejected.getAttribute("errors")).isEqualTo(List.of("El archivo supera los 5 MB."));
    assertThat(unavailable.getAttribute("errors")).isEqualTo(List.of(FileMessages.UNAVAILABLE));
  }

  @Test
  void aMissingFileReloadsThePageWithANoticeAndForbiddenHasItsOwnPage() {
    var model = new ConcurrentModel();

    assertThat(controller.notFound(who(), model)).isEqualTo("cv/candidate-cv");
    assertThat(model.getAttribute("notice")).asString().contains("No encontramos");
    assertThat(controller.forbidden()).isEqualTo("cv/candidate-forbidden");
  }

  // HTMX reemplaza la zona de errores solo con un 422 (htmx-config de la página).
  @Test
  void eachErrorHandlerDeclaresTheStatusThePageExpects() throws Exception {
    assertThat(statusOf("rejected", FileRejectedException.class, Model.class))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(statusOf("unavailable", Model.class)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(statusOf("notFound", Principal.class, Model.class)).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(statusOf("forbidden")).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private static HttpStatus statusOf(String method, Class<?>... types) throws Exception {
    Method handler = CvScreenController.class.getDeclaredMethod(method, types);
    return handler.getAnnotation(ResponseStatus.class).value();
  }
}
