package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;

/**
 * Lo que la pantalla dice cuando un archivo no se acepta (ADR-0021). Nunca repite el nombre del
 * archivo: puede ser un dato personal (BR-DATA-01).
 */
final class FileMessages {

  static final String UNAVAILABLE =
      "No pudimos procesar el archivo en este momento. Inténtalo de nuevo en unos minutos.";

  private FileMessages() {}

  static String cv(Reason reason) {
    return switch (reason) {
      case TOO_LARGE -> "El archivo supera los 5 MB.";
      case UNSUPPORTED_TYPE -> "Solo se aceptan CV en PDF o en Word (.docx) sin macros.";
      case MALWARE_DETECTED -> "El antivirus encontró un problema en el archivo y no se guardó.";
      case LIMIT_REACHED -> "Ya tienes el máximo de versiones de tu CV guardadas.";
    };
  }

  static String photo(Reason reason) {
    return switch (reason) {
      case TOO_LARGE -> "La imagen supera los 2 MB.";
      case UNSUPPORTED_TYPE ->
          "Solo se aceptan imágenes PNG o JPEG que se puedan abrir, de tamaño razonable.";
      case MALWARE_DETECTED -> "El antivirus encontró un problema en la imagen y no se guardó.";
      case LIMIT_REACHED -> "No se pudo guardar la imagen.";
    };
  }
}
