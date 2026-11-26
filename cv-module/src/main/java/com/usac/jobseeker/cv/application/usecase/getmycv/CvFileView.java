package com.usac.jobseeker.cv.application.usecase.getmycv;

import com.usac.jobseeker.cv.domain.model.CvFile;
import java.time.Instant;
import java.util.UUID;

/**
 * Una versión del CV tal como la ve su dueño: sin clave del almacén ni URL, que solo se emite al
 * pedir la descarga (ADR-0021 §3).
 *
 * @param type `PDF` o `DOCX`
 */
public record CvFileView(
    UUID id, String displayName, String type, long sizeBytes, Instant uploadedAt, boolean current) {

  public static CvFileView of(CvFile file) {
    return new CvFileView(
        file.id(),
        file.displayName(),
        file.type().name(),
        file.sizeBytes(),
        file.uploadedAt(),
        file.current());
  }
}
