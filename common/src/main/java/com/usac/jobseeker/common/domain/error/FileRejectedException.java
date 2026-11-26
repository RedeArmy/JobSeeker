package com.usac.jobseeker.common.domain.error;

import java.util.Objects;

/**
 * Un archivo subido no se acepta (ADR-0021 §2 y §4). `app` lo traduce a `422 FILE_REJECTED` con el
 * motivo, para que la interfaz diga qué corregir. El mensaje nunca lleva el nombre del archivo, que
 * puede ser un dato personal (BR-DATA-01).
 */
public final class FileRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Por qué se rechaza; los nombres son estables porque viajan en la API. */
  public enum Reason {
    /** Supera el tamaño máximo de su propósito. */
    TOO_LARGE,
    /** Su contenido real no es de un tipo aceptado, diga lo que diga su extensión. */
    UNSUPPORTED_TYPE,
    /** El antivirus lo marcó como malicioso. */
    MALWARE_DETECTED,
    /** La cuenta ya tiene el máximo de versiones guardadas (ADR-0021 §5). */
    LIMIT_REACHED
  }

  private final Reason rejectionReason;

  public FileRejectedException(Reason reason) {
    super("Archivo rechazado: " + Objects.requireNonNull(reason, "reason"));
    this.rejectionReason = reason;
  }

  public Reason reason() {
    return rejectionReason;
  }
}
