package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.application.usecase.getmycv.CvFileView;
import com.usac.jobseeker.cv.application.usecase.getmycv.MyCvView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contrato JSON del CV: metadatos de cada versión, nunca la clave del almacén ni una URL. */
@Schema(description = "Una versión del CV.")
record CvFileResponse(
    @Schema(description = "Identificador de la versión; es el que se descarga.") UUID id,
    @Schema(description = "Nombre saneado, con la extensión del tipo detectado.") String name,
    @Schema(
            description = "Tipo detectado por el contenido.",
            allowableValues = {"PDF", "DOCX"})
        String type,
    @Schema(description = "Tamaño en bytes.") long sizeBytes,
    @Schema(description = "Cuándo se subió.") Instant uploadedAt,
    @Schema(description = "Si es la versión vigente.") boolean current) {

  static CvFileResponse from(CvFileView view) {
    return new CvFileResponse(
        view.id(),
        view.displayName(),
        view.type(),
        view.sizeBytes(),
        view.uploadedAt(),
        view.current());
  }

  /** El CV vigente y todas las versiones, de la más reciente a la más antigua. */
  @Schema(description = "Mi CV: la versión vigente y las anteriores.")
  record MyCv(
      @Schema(description = "La vigente; nula si aún no se subió ninguna.") CvFileResponse current,
      List<CvFileResponse> versions,
      @Schema(description = "Cuántas versiones puede guardar la cuenta.") int maxVersions) {

    static MyCv from(MyCvView view) {
      return new MyCv(
          view.current().map(CvFileResponse::from).orElse(null),
          view.versions().stream().map(CvFileResponse::from).toList(),
          view.maxVersions());
    }
  }
}
