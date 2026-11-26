package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Cada motivo de rechazo tiene su mensaje, distinto para el CV y para la foto. */
class FileMessagesTest {

  @Test
  void everyReasonHasAMessageForTheCvAndForThePhoto() {
    for (Reason reason : Reason.values()) {
      assertThat(FileMessages.cv(reason)).isNotBlank().endsWith(".");
      assertThat(FileMessages.photo(reason)).isNotBlank().endsWith(".");
    }
    assertThat(Arrays.stream(Reason.values()).map(FileMessages::cv).distinct())
        .hasSize(Reason.values().length);
    assertThat(FileMessages.cv(Reason.TOO_LARGE)).contains("5 MB");
    assertThat(FileMessages.photo(Reason.TOO_LARGE)).contains("2 MB");
    assertThat(FileMessages.photo(Reason.UNSUPPORTED_TYPE)).contains("PNG o JPEG");
    assertThat(FileMessages.cv(Reason.MALWARE_DETECTED)).contains("antivirus");
    assertThat(FileMessages.photo(Reason.MALWARE_DETECTED)).contains("antivirus");
    assertThat(FileMessages.cv(Reason.LIMIT_REACHED)).contains("máximo");
    assertThat(FileMessages.photo(Reason.LIMIT_REACHED)).isEqualTo("No se pudo guardar la imagen.");
  }
}
