package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.rules.FileType;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Una versión del CV de un candidato (ADR-0021 §5, `tdd/cv-module.md` §10.7). Es inmutable: subir
 * otra crea otra versión, porque una postulación guarda la versión con que se envió y el reclutador
 * debe ver lo que recibió. El binario está en el almacén; aquí solo su referencia.
 *
 * @param objectKey clave opaca del almacén (`cv/{uuid}`)
 * @param displayName nombre saneado con la extensión del tipo detectado
 * @param sha256 huella del contenido, para no crear otra versión con el mismo archivo
 */
public record CvFile(
    UUID id,
    CandidateProfileId profileId,
    String objectKey,
    String displayName,
    FileType type,
    long sizeBytes,
    String sha256,
    boolean current,
    Instant uploadedAt) {

  /** Lo que se acepta como CV (ADR-0021 §4, decisión R2). */
  public static final Set<FileType> ACCEPTED_TYPES = Set.of(FileType.PDF, FileType.DOCX);

  private static final Pattern SHA256_FORMAT = Pattern.compile("^[0-9a-f]{64}$");

  public CvFile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(objectKey, "objectKey");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(uploadedAt, "uploadedAt");
    if (!ACCEPTED_TYPES.contains(type)) {
      throw new IllegalArgumentException("Un CV es PDF o DOCX");
    }
    if (sizeBytes <= 0) {
      throw new IllegalArgumentException("Un CV no está vacío");
    }
    if (sha256 == null || !SHA256_FORMAT.matcher(sha256).matches()) {
      throw new IllegalArgumentException("Huella SHA-256 no válida");
    }
  }
}
