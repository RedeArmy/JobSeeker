package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import com.usac.jobseeker.common.domain.rules.FileType;
import java.util.Objects;

/**
 * Puerto de salida que re-codifica la foto (ADR-0021 §2): decodifica la imagen, rechaza la que no
 * se puede leer o tiene demasiados píxeles, la reduce y la vuelve a codificar. Al re-codificarla se
 * pierden los metadatos EXIF, incluida la ubicación GPS de una foto tomada con el móvil.
 *
 * <p>Lanza {@code FileRejectedException} con {@code UNSUPPORTED_TYPE} si la imagen no es válida.
 */
public interface PhotoProcessorPort {

  ProcessedPhoto process(byte[] content, FileType type);

  /** La imagen lista para guardar, en el mismo tipo que llegó. */
  record ProcessedPhoto(byte[] content, FileType type) {
    public ProcessedPhoto {
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(type, "type");
    }
  }
}
