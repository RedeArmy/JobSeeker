package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoCommand;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.UploadProfilePhotoCommand;
import java.io.UncheckedIOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** La API de la foto traduce el token al caso de uso y el resultado a HTTP. */
class ProfilePhotoControllerTest {

  private final UUID caller = UUID.randomUUID();
  private final List<Object> calls = new ArrayList<>();
  private final PhotoFakes photos = new PhotoFakes();

  private final ProfilePhotoController controller =
      new ProfilePhotoController(
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
  void uploadingAnswers201WithTheTypeAndTheTag() {
    var response = controller.upload(who(), WebFakes.file("yo.png", PhotoFakes.BYTES));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody())
        .isEqualTo(new ProfilePhotoController.PhotoResponse("PNG", "abcdef123456"));
    assertThat(calls)
        .singleElement()
        .isInstanceOfSatisfying(
            UploadProfilePhotoCommand.class,
            c -> assertThat(c.accountId().value()).isEqualTo(caller));
  }

  @Test
  void aMissingOrEmptyFileIsAValidationError() {
    Principal principal = who();
    var empty = WebFakes.file("yo.png", new byte[0]);

    for (var file : java.util.Arrays.asList(null, empty)) {
      assertThatThrownBy(() -> controller.upload(principal, file))
          .isInstanceOfSatisfying(
              ValidationFailedException.class,
              e ->
                  assertThat(e.violations())
                      .containsExactly(new FieldViolation("file", "REQUIRED")));
    }
    assertThat(calls).isEmpty();
  }

  @Test
  void aFileThatCannotBeReadIsAnInputError() {
    Principal principal = who();
    var broken = WebFakes.file("yo.png", PhotoFakes.BYTES, true);

    assertThatThrownBy(() -> controller.upload(principal, broken))
        .isInstanceOf(UncheckedIOException.class);
  }

  // Privada: es la foto de una persona y no debe guardarla una caché compartida.
  @Test
  void thePhotoIsServedWithItsTypeAndPrivateCaching() {
    var response = controller.photo(who());

    assertThat(response.getBody()).isEqualTo(PhotoFakes.BYTES);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=300, private");
  }

  @Test
  void withoutAPhotoTheReadIsNotFound() {
    photos.present = false;
    Principal principal = who();

    assertThatThrownBy(() -> controller.photo(principal)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void removingAnswers204() {
    var response = controller.remove(who());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(calls).singleElement().isInstanceOf(RemoveProfilePhotoCommand.class);
  }
}
