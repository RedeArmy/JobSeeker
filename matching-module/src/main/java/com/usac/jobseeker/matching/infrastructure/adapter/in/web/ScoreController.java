package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreQuery;
import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreResult;
import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreUseCase;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreQuery;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreResult;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreUseCase;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * La compatibilidad del **candidato** con una vacante que puede ver (BR-VIS-01) y el «qué pasaría
 * si» (REQ-C-01). Delgado y sin lógica: el candidato sale del token, nunca de la ruta ni del
 * cuerpo, y la visibilidad, el cálculo y la explicación son de los casos de uso.
 */
@RestController
@Tag(name = "matching", description = "Compatibilidad CV-vacante (módulo matching).")
public class ScoreController {

  private static final String ERROR_ENVELOPE = "#/components/schemas/ErrorEnvelope";
  private static final String BASE = "/api/v1/matching/jobs/{jobId}/score";

  private final ExplainScoreUseCase explainScore;
  private final WhatIfScoreUseCase whatIfScore;

  public ScoreController(ExplainScoreUseCase explain, WhatIfScoreUseCase whatIf) {
    this.explainScore = explain;
    this.whatIfScore = whatIf;
  }

  /** Lo que el candidato quiere probar; no es parte de su perfil y no se guarda. */
  @Schema(
      description = "Habilidades e idiomas que declararías, para ver cómo cambiaría tu puntaje.")
  public record WhatIfRequest(List<SkillInput> skills, List<LanguageInput> languages) {

    @Schema(description = "Una habilidad hipotética.")
    public record SkillInput(
        @Schema(example = "Kubernetes") String name,
        @Schema(description = "Nivel de 1 a 5; opcional.", example = "3") Integer level) {}

    @Schema(description = "Un idioma hipotético.")
    public record LanguageInput(
        @Schema(description = "Código de idioma.", example = "en") String language,
        @Schema(description = "A1 a C2 o NATIVE.", example = "B2") String proficiency) {}
  }

  @GetMapping(BASE)
  @Operation(
      summary = "Mi compatibilidad con una vacante",
      description =
          "Solo para cuentas con capacidad de candidato y sobre vacantes publicadas o cerradas que no"
              + " son suyas. Devuelve el porcentaje con su desglose y explicaciones. Mientras falte el"
              + " vector del perfil o de la vacante responde `202` con `PENDING_EMBEDDING`: se calcula"
              + " cuando existan, nunca a medias. `updating` avisa de un cambio en cola.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "El puntaje con su desglose.")
  @ApiResponse(responseCode = "202", description = "Aún no se puede calcular: `PENDING_EMBEDDING`.")
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale (firma, vigencia, audiencia o tipo).",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN_CAPABILITY`: la cuenta no es candidata.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "La vacante no existe, no la ven los candidatos o es del propio solicitante.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "409",
      description = "`INVALID_STATE`: aún no tienes un perfil legible.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Límite de peticiones; reintentar más tarde.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public ResponseEntity<ScoreResponse> score(Principal principal, @PathVariable String jobId) {
    ExplainScoreResult result =
        explainScore.execute(new ExplainScoreQuery(candidate(principal), job(jobId)));
    return ResponseEntity.status(
            result.status() == ExplainScoreResult.Status.OK ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(ScoreResponse.from(result));
  }

  @PostMapping(BASE + "/what-if")
  @Operation(
      summary = "¿Qué pasaría con mi puntaje si declarara…?",
      description =
          "Calcula, sin guardar nada ni cambiar tu perfil, tu puntaje con las habilidades e idiomas"
              + " que pruebas. Solo calcula lo que tú pides: no sugiere declarar lo que no tienes."
              + " Mismas reglas de visibilidad que el puntaje real. Hasta 20 habilidades y 20 idiomas.",
      security = @SecurityRequirement(name = "bearer"))
  @ApiResponse(responseCode = "200", description = "El puntaje actual y el hipotético.")
  @ApiResponse(responseCode = "202", description = "Aún no se puede calcular: `PENDING_EMBEDDING`.")
  @ApiResponse(
      responseCode = "400",
      description =
          "`VALIDATION_FAILED`: demasiadas hipótesis, un nombre vacío o un nivel inválido.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "401",
      description = "Falta el token, o no vale.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN_CAPABILITY`: la cuenta no es candidata.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "La vacante no existe, no la ven los candidatos o es del propio solicitante.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "409",
      description = "`INVALID_STATE`: aún no tienes un perfil legible.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "429",
      description = "Límite de peticiones; reintentar más tarde.",
      content = @Content(schema = @Schema(ref = ERROR_ENVELOPE)))
  public ResponseEntity<ScoreResponse.WhatIf> whatIf(
      Principal principal, @PathVariable String jobId, @RequestBody WhatIfRequest request) {
    List<CandidateSignals.Skill> skills =
        request.skills() == null
            ? List.of()
            : request.skills().stream()
                .map(s -> new CandidateSignals.Skill(s.name(), s.level()))
                .toList();
    List<CandidateSignals.Language> languages =
        request.languages() == null
            ? List.of()
            : request.languages().stream()
                .map(l -> new CandidateSignals.Language(l.language(), l.proficiency()))
                .toList();
    WhatIfScoreResult result =
        whatIfScore.execute(
            new WhatIfScoreQuery(candidate(principal), job(jobId), skills, languages));
    return ResponseEntity.status(
            result.status() == WhatIfScoreResult.Status.OK ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(ScoreResponse.WhatIf.from(result));
  }

  // `sub` ya fue validado como UUID por el decodificador de la cadena (`SubjectValidator`).
  private static AccountId candidate(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }

  // Un identificador que no es un UUID no puede ser de una vacante: es inexistente, no inválido.
  private static JobPostingId job(String raw) {
    try {
      return new JobPostingId(UUID.fromString(raw));
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }
}
