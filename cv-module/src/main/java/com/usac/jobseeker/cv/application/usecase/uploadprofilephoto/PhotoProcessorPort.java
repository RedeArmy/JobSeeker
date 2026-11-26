package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import com.usac.jobseeker.common.domain.rules.FileType;
import java.util.Arrays;
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

  /**
   * La imagen lista para guardar, en el mismo tipo que llegó. Copia el arreglo al entrar y al salir
   * y compara por contenido, como {@code ProfilePhotoContent}: un registro con un arreglo no lo
   * hace.
   */
  record ProcessedPhoto(byte[] content, FileType type) {
    public ProcessedPhoto {
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(type, "type");
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ProcessedPhoto(byte[] bytes, FileType kind)
          && type == kind
          && Arrays.equals(content, bytes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
      return "ProcessedPhoto[" + type + ", bytes=" + content.length + "]";
    }
  }
}
