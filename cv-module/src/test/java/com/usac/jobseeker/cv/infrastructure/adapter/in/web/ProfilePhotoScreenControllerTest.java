package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoCommand;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.UploadProfilePhotoCommand;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.ui.ConcurrentModel;

/**
 * La sección de la foto: cada respuesta es la sección entera, y un error vuelve a ella con
 * `HX-Retarget` en vez de ir a la zona de errores del formulario del perfil.
 */
class ProfilePhotoScreenControllerTest {

  private static final String SECTION = "cv/profile-photo :: section";

  private final UUID caller = UUID.randomUUID();
  private final List<Object> calls = new ArrayList<>();
  private final PhotoFakes photos = new PhotoFakes();
  private final WebFakes.Response response = new WebFakes.Response();

  private final ProfilePhotoScreenController controller =
      new ProfilePhotoScreenController(
          command -> {
            calls.add(command);
            return PhotoFakes.VIEW;
          },
          photos,
          calls::add);

  private Principal who() {
    return caller::toString;
  }

  @Test
  void theSectionShowsThePhotoOrNothing() {
    var withPhoto = new ConcurrentModel();
    var without = new ConcurrentModel();

    assertThat(controller.section(who(), withPhoto)).isEqualTo(SECTION);
    photos.present = false;
    controller.section(who(), without);

    assertThat(withPhoto.getAttribute("photo")).isEqualTo(PhotoFakes.VIEW);
    assertThat(without.getAttribute("photo")).isNull();
  }

  @Test
  void theImageIsServedFromTheOwnOriginWithPrivateCaching() {
    var image = controller.image(who());

    assertThat(image.getBody()).isEqualTo(PhotoFakes.BYTES);
    assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(image.getHeaders().getCacheControl()).isEqualTo("max-age=300, private");
    photos.present = false;
    Principal principal = who();
    assertThatThrownBy(() -> controller.image(principal)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void uploadingAndRemovingAnswerTheSectionWithANotice() {
    var uploaded = new ConcurrentModel();
    var removed = new ConcurrentModel();

    assertThat(
            controller.upload(
                who(), uploaded, response.servlet(), WebFakes.file("yo.png", PhotoFakes.BYTES)))
        .isEqualTo(SECTION);
    assertThat(controller.remove(who(), removed)).isEqualTo(SECTION);

    assertThat(uploaded.getAttribute("notice")).asString().contains("guardada");
    assertThat(removed.getAttribute("notice")).asString().contains("Quitamos");
    assertThat(calls.get(0)).isInstanceOf(UploadProfilePhotoCommand.class);
    assertThat(calls.get(1)).isInstanceOf(RemoveProfilePhotoCommand.class);
    assertThat(response.headers).isEmpty();
  }

  @Test
  void noFileIsExplainedInsideTheSection() {
    var model = new ConcurrentModel();

    controller.upload(who(), model, response.servlet(), null);

    assertRetargeted(model, "Elige una imagen");
    assertThat(calls).isEmpty();
  }

  @Test
  void aRejectionAndAnUnavailableScannerAreExplainedInsideTheSection() {
    var rejected = new ConcurrentModel();
    var unavailable = new ConcurrentModel();

    assertThat(
            controller.rejected(
                new FileRejectedException(Reason.UNSUPPORTED_TYPE),
                who(),
                rejected,
                response.servlet()))
        .isEqualTo(SECTION);
    assertRetargeted(rejected, "PNG o JPEG");
    assertThat(controller.unavailable(who(), unavailable, response.servlet())).isEqualTo(SECTION);
    assertRetargeted(unavailable, "en este momento");
  }

  @Test
  void withoutAProfessionalProfileTheSectionAsksToSaveItFirst() {
    var model = new ConcurrentModel();

    assertThat(controller.notFound(who(), model)).isEqualTo(SECTION);
    assertThat(model.getAttribute("error")).asString().contains("perfil profesional");
  }

  private void assertRetargeted(ConcurrentModel model, String message) {
    assertThat(response.status).isEqualTo(422);
    assertThat(response.headers)
        .containsAllEntriesOf(Map.of("HX-Retarget", "#photo-section", "HX-Reswap", "outerHTML"));
    assertThat(model.getAttribute("error")).asString().contains(message);
  }
}
