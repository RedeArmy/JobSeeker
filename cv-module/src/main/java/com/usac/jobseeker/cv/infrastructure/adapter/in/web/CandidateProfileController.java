package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileQuery;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesCommand;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesUseCase;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsCommand;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsUseCase;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsCommand;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsUseCase;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.SaveCandidateProfileCommand;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.SaveCandidateProfileResult;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.SaveCandidateProfileUseCase;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * El perfil de candidato de la propia cuenta: sus datos y sus tres conjuntos. Delgado y sin lógica:
 * traduce HTTP al caso de uso y su resultado al contrato. La identidad sale del token, nunca de la
 * ruta ni del cuerpo, y toda ruta exige la capacidad de candidato (BR-ACCT-04).
 *
 * <p>Toda escritura devuelve el **perfil completo** con su versión nueva. El límite de tasa, el
 * sobre de error y las métricas HTTP son transversales y no se repiten aquí.
 */
@RestController
@Tag(name = "cv", description = "Perfil profesional y de candidato (módulo cv).")
public class CandidateProfileController {

  private static final String PATH = "/api/v1/me/candidate-profile";
  private static final String BEARER = ApiDocs.BEARER;
  private static final String WHOLE_PROFILE = "Devuelve el perfil completo con su versión nueva.";

  private final GetMyCandidateProfileUseCase getProfile;
  private final SaveCandidateProfileUseCase saveProfile;
  private final ReplaceCandidateSkillsUseCase skills;
  private final ReplaceCandidateLanguagesUseCase languages;
  private final ReplaceWorkAuthorizationsUseCase authorizations;

  public CandidateProfileController(
      GetMyCandidateProfileUseCase getProfile,
      SaveCandidateProfileUseCase saveProfile,
      ReplaceCandidateSkillsUseCase skills,
      ReplaceCandidateLanguagesUseCase languages,
      ReplaceWorkAuthorizationsUseCase authorizations) {
    this.getProfile = getProfile;
    this.saveProfile = saveProfile;
    this.skills = skills;
    this.languages = languages;
    this.authorizations = authorizations;
  }

  @GetMapping(PATH)
  @Operation(
      summary = "Consultar mi perfil de candidato",
      description =
          "Devuelve el perfil de candidato de quien hace la petición. Si todavía no lo creó, responde"
              + " 200 con `created: false` y `version: 0`: no es un error. Exige la capacidad de"
              + " candidato.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "El perfil, o el estado «sin crear».")
  @CandidateWriteResponses
  public CandidateProfileResponse myProfile(Principal principal) {
    return CandidateProfileResponse.from(
        getProfile.execute(new GetMyCandidateProfileQuery(caller(principal))));
  }

  @PutMapping(PATH)
  @Operation(
      summary = "Crear o actualizar mi perfil de candidato",
      description =
          "Guarda el contacto y los datos de elegibilidad. Al crear se envía `version: 0` y responde"
              + " 201; al editar se envía la versión leída y responde 200. Antes hay que crear el"
              + " perfil profesional. Si los datos son iguales a los guardados no cambia nada. No toca"
              + " la descubribilidad ni la disponibilidad.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Perfil actualizado.")
  @ApiResponse(responseCode = ApiDocs.CREATED, description = "Perfil creado.")
  @CandidateWriteResponses
  public ResponseEntity<CandidateProfileResponse> saveMyProfile(
      Principal principal, @RequestBody SaveCandidateProfileRequest body) {
    SaveCandidateProfileResult result =
        saveProfile.execute(
            new SaveCandidateProfileCommand(
                caller(principal),
                body.contactEmail(),
                body.contactPhone(),
                body.countryOfResidence(),
                body.desiredModality(),
                body.preferredWorkSchedule(),
                body.relocationWillingness(),
                body.salaryMin(),
                body.salaryMax(),
                body.salaryCurrency(),
                body.salaryPeriod(),
                version(body.version())));
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(CandidateProfileResponse.from(result.profile()));
  }

  @PutMapping(PATH + "/skills")
  @Operation(
      summary = "Reemplazar mis habilidades",
      description = "Reemplaza el conjunto entero de habilidades. " + WHOLE_PROFILE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Conjunto reemplazado; perfil completo.")
  @CandidateWriteResponses
  public CandidateProfileResponse replaceSkills(
      Principal principal, @RequestBody ReplaceSetsRequests.Skills body) {
    List<CandidateSkill> items =
        required("skills", body.skills()).stream()
            .map(e -> new CandidateSkill(orEmpty(e.name()), e.level()))
            .toList();
    return CandidateProfileResponse.from(
        skills.execute(
            new ReplaceCandidateSkillsCommand(caller(principal), version(body.version()), items)));
  }

  @PutMapping(PATH + "/languages")
  @Operation(
      summary = "Reemplazar mis idiomas",
      description = "Reemplaza el conjunto entero de idiomas. " + WHOLE_PROFILE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Conjunto reemplazado; perfil completo.")
  @CandidateWriteResponses
  public CandidateProfileResponse replaceLanguages(
      Principal principal, @RequestBody ReplaceSetsRequests.Languages body) {
    List<CandidateLanguage> items =
        required("languages", body.languages()).stream()
            .map(e -> new CandidateLanguage(e.language(), e.proficiency()))
            .toList();
    return CandidateProfileResponse.from(
        languages.execute(
            new ReplaceCandidateLanguagesCommand(
                caller(principal), version(body.version()), items)));
  }

  @PutMapping(PATH + "/work-authorizations")
  @Operation(
      summary = "Reemplazar mis autorizaciones de trabajo",
      description =
          "Reemplaza el conjunto entero de países con autorización de trabajo. " + WHOLE_PROFILE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Conjunto reemplazado; perfil completo.")
  @CandidateWriteResponses
  public CandidateProfileResponse replaceWorkAuthorizations(
      Principal principal, @RequestBody ReplaceSetsRequests.WorkAuthorizations body) {
    List<String> countries = required("countries", body.countries());
    return CandidateProfileResponse.from(
        authorizations.execute(
            new ReplaceWorkAuthorizationsCommand(
                caller(principal), version(body.version()), countries)));
  }

  // Un conjunto ausente no es lo mismo que uno vacío: vaciar es una decisión explícita, y un campo
  // olvidado no debe borrar lo que había.
  private static <T> List<T> required(String field, List<T> value) {
    if (value == null) {
      throw new ValidationFailedException(List.of(new FieldViolation(field, "REQUIRED")));
    }
    return value;
  }

  private static String orEmpty(String text) {
    return text == null ? "" : text;
  }

  // La versión es obligatoria en toda escritura: sin ella no hay forma de saber qué se leyó.
  private static long version(Long value) {
    if (value == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "REQUIRED")));
    }
    return value;
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }
}
