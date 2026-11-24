package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Los cuerpos de los tres reemplazos de conjunto del perfil de candidato. */
interface ReplaceSetsRequests {

  @Schema(description = "El conjunto completo de habilidades; reemplaza al anterior.")
  record Skills(
      @Schema(description = "Vacío para vaciar el conjunto. Hasta 100.") List<SkillEntry> skills,
      @Schema(
              description = "La versión del perfil de candidato que se leyó. Obligatoria.",
              example = "3")
          Long version) {}

  @Schema(description = "Una habilidad con su nivel opcional.")
  record SkillEntry(
      @Schema(
              description = "Hasta 100 caracteres; no se repite aunque cambie la capitalización.",
              example = "Java")
          String name,
      @Schema(description = "De 1 (básico) a 4 (experto), opcional.", example = "3")
          Integer level) {}

  @Schema(description = "El conjunto completo de idiomas; reemplaza al anterior.")
  record Languages(
      @Schema(description = "Vacío para vaciar el conjunto. Hasta 30.")
          List<LanguageEntry> languages,
      @Schema(
              description = "La versión del perfil de candidato que se leyó. Obligatoria.",
              example = "3")
          Long version) {}

  @Schema(description = "Un idioma y su nivel.")
  record LanguageEntry(
      @Schema(description = "ISO 639-1; no se repite.", example = "en") String language,
      LanguageProficiency proficiency) {}

  @Schema(description = "El conjunto completo de países con autorización de trabajo.")
  record WorkAuthorizations(
      @Schema(
              description =
                  "Códigos ISO 3166-1 alfa-2, sin repetir. Vacío para vaciarlo. Hasta 50.",
              example = "[\"GT\", \"MX\"]")
          List<String> countries,
      @Schema(
              description = "La versión del perfil de candidato que se leyó. Obligatoria.",
              example = "3")
          Long version) {}
}
