package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import com.usac.jobseeker.cv.domain.model.SalaryExpectation;
import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.domain.model.WorkSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Respuesta del perfil de candidato: el contrato público, separado del modelo interno. */
@Schema(description = "El perfil de candidato de la cuenta, o el estado «aún sin crear».")
public record CandidateProfileResponse(
    @Schema(
            description =
                "`false` si la cuenta todavía no creó su perfil de candidato: los demás campos"
                    + " vienen vacíos y `version` es 0, que es lo que se envía al crearlo.")
        boolean created,
    @Schema(description = "Identificador del perfil; vacío si aún no existe.") String id,
    @Schema(description = "Correo de contacto para postular.", example = "ana@example.com")
        String contactEmail,
    @Schema(description = "Teléfono de contacto, opcional.", example = "+502 5555-1234")
        String contactPhone,
    @Schema(description = "País de residencia, ISO 3166-1 alfa-2.", example = "GT")
        String countryOfResidence,
    @Schema(description = "Modalidad de trabajo que busca.") WorkModality desiredModality,
    @Schema(description = "Jornada que prefiere.") WorkSchedule preferredWorkSchedule,
    @Schema(description = "Si está dispuesto a reubicarse.") boolean relocationWillingness,
    @Schema(description = "Expectativa salarial con su moneda y periodo; ausente si no la declaró.")
        Salary salary,
    @Schema(description = "Si el perfil aparece en las sugerencias a reclutadores (BR-DATA-03).")
        boolean discoverable,
    @Schema(description = "Si busca empleo activamente (BR-CAND-04).")
        AvailabilityStatus availabilityStatus,
    @Schema(
            description =
                "Versión del perfil de candidato. Debe devolverse al guardar o reemplazar un"
                    + " conjunto: si otra edición se guardó antes, se rechaza con `409 STALE_STATE`.",
            example = "3")
        long version,
    @Schema(description = "Última edición, en UTC (ISO-8601).", example = "2026-11-23T16:30:00Z")
        String updatedAt,
    @Schema(description = "Habilidades declaradas.") List<SkillItem> skills,
    @Schema(description = "Idiomas y su nivel.") List<LanguageItem> languages,
    @Schema(
            description = "Países con autorización de trabajo, ISO 3166-1 alfa-2.",
            example = "[\"GT\", \"MX\"]")
        List<String> workAuthorizations) {

  @Schema(description = "Expectativa salarial: un rango con su moneda y su periodo (BR-CAND-07).")
  public record Salary(
      @Schema(example = "3000") BigDecimal min,
      @Schema(example = "4500") BigDecimal max,
      @Schema(description = "Moneda ISO 4217.", example = "USD") String currency,
      SalaryPeriod period) {

    static Salary from(SalaryExpectation s) {
      return s == null ? null : new Salary(s.min(), s.max(), s.currency(), s.period());
    }
  }

  @Schema(description = "Una habilidad; el nivel va de 1 (básico) a 4 (experto) y es opcional.")
  public record SkillItem(String name, Integer level) {}

  @Schema(description = "Un idioma y el nivel de dominio.")
  public record LanguageItem(
      @Schema(description = "ISO 639-1.", example = "en") String language,
      LanguageProficiency proficiency) {}

  /** El perfil aún no existe: lo único que el cliente necesita es saberlo y la versión inicial. */
  static CandidateProfileResponse notCreated() {
    return new CandidateProfileResponse(
        false, null, null, null, null, null, null, false, null, false, null, 0, null, List.of(),
        List.of(), List.of());
  }

  static CandidateProfileResponse from(CandidateProfileView view) {
    var d = view.details();
    return new CandidateProfileResponse(
        true,
        view.id().toString(),
        d.contact().email(),
        d.contact().phone(),
        d.countryOfResidence(),
        d.desiredModality(),
        d.preferredWorkSchedule(),
        d.relocationWillingness(),
        Salary.from(d.salary()),
        view.discoverable(),
        view.availability(),
        view.version(),
        view.updatedAt().toString(),
        view.collections().skills().stream().map(CandidateProfileResponse::skill).toList(),
        view.collections().languages().stream().map(CandidateProfileResponse::language).toList(),
        view.collections().workAuthorizations());
  }

  static CandidateProfileResponse from(Optional<CandidateProfileView> view) {
    return view.map(CandidateProfileResponse::from).orElseGet(CandidateProfileResponse::notCreated);
  }

  private static SkillItem skill(CandidateSkill s) {
    return new SkillItem(s.name(), s.level());
  }

  private static LanguageItem language(CandidateLanguage l) {
    return new LanguageItem(l.language(), l.proficiency());
  }
}
