package com.usac.jobseeker.job.infrastructure.adapter.in.web;

import static com.usac.jobseeker.job.infrastructure.adapter.in.web.WebInputs.caller;
import static com.usac.jobseeker.job.infrastructure.adapter.in.web.WebInputs.postingId;
import static com.usac.jobseeker.job.infrastructure.adapter.in.web.WebInputs.required;
import static com.usac.jobseeker.job.infrastructure.adapter.in.web.WebInputs.version;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobDetailsInput;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.createjobposting.CreateJobPostingUseCase;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingQuery;
import com.usac.jobseeker.job.application.usecase.getjobposting.GetJobPostingUseCase;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsCommand;
import com.usac.jobseeker.job.application.usecase.managejobrequirements.ManageJobRequirementsUseCase;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsCommand;
import com.usac.jobseeker.job.application.usecase.setacceptingapplications.SetAcceptingApplicationsUseCase;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageCommand;
import com.usac.jobseeker.job.application.usecase.setrecruitingstage.SetRecruitingStageUseCase;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.transitionjobposting.TransitionJobPostingUseCase;
import com.usac.jobseeker.job.application.usecase.updatejobposting.UpdateJobPostingCommand;
import com.usac.jobseeker.job.application.usecase.updatejobposting.UpdateJobPostingUseCase;
import com.usac.jobseeker.job.domain.model.JobRequirementDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * La vacante y su ciclo de vida manual. Delgado y sin lógica: traduce HTTP al caso de uso y su
 * resultado al contrato. El dueño sale del token, nunca de la ruta ni del cuerpo, y una vacante de
 * otro reclutador responde `404`, no `403`: no se confirma que exista (BR-VIS-03).
 *
 * <p>Toda escritura devuelve la vacante **completa** con su versión nueva. El límite de tasa, el
 * sobre de error y las métricas HTTP son transversales y no se repiten aquí.
 */
@RestController
@Tag(name = "job", description = "Vacantes y su ciclo de vida (módulo job).")
public class JobPostingController {

  private static final String PATH = "/api/v1/job/postings";
  private static final String ONE = PATH + "/{id}";
  private static final String BEARER = ApiDocs.BEARER;
  private static final String WHOLE = "Devuelve la vacante completa con su versión nueva.";

  private final CreateJobPostingUseCase create;
  private final UpdateJobPostingUseCase update;
  private final GetJobPostingUseCase get;
  private final ManageJobRequirementsUseCase requirements;
  private final TransitionJobPostingUseCase transitions;
  private final SetAcceptingApplicationsUseCase accepting;
  private final SetRecruitingStageUseCase stage;

  public JobPostingController(
      CreateJobPostingUseCase create,
      UpdateJobPostingUseCase update,
      GetJobPostingUseCase get,
      ManageJobRequirementsUseCase requirements,
      TransitionJobPostingUseCase transitions,
      SetAcceptingApplicationsUseCase accepting,
      SetRecruitingStageUseCase stage) {
    this.create = create;
    this.update = update;
    this.get = get;
    this.requirements = requirements;
    this.transitions = transitions;
    this.accepting = accepting;
    this.stage = stage;
  }

  @PostMapping(PATH)
  @Operation(
      summary = "Crear una vacante",
      description =
          "Nace en borrador, con `version: 1`. Exige la capacidad de reclutador (BR-JOB-01). Para"
              + " publicarla hace falta al menos una habilidad o formación (BR-JOB-02).",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = "Vacante creada en borrador.")
  @JobWriteResponses
  public ResponseEntity<JobPostingResponse> createPosting(
      Principal principal, @RequestBody JobRequests.Posting body) {
    var view = create.execute(new CreateJobPostingCommand(caller(principal), input(body)));
    return ResponseEntity.status(HttpStatus.CREATED).body(JobPostingResponse.from(view));
  }

  @GetMapping(ONE)
  @Operation(
      summary = "Consultar una vacante",
      description =
          "El dueño la ve en cualquier estado salvo eliminada. Un candidato solo ve las publicadas"
              + " y las cerradas (BR-JOB-11), sin los campos de gestión; para todo lo demás la"
              + " vacante no existe.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "La vacante con sus requisitos.")
  @JobWriteResponses
  public JobPostingResponse getPosting(Principal principal, @PathVariable String id) {
    AccountId viewer = caller(principal);
    return JobPostingResponse.from(get.execute(new GetJobPostingQuery(viewer, postingId(id))));
  }

  @PutMapping(ONE)
  @Operation(
      summary = "Editar los datos de una vacante propia",
      description =
          "Reemplaza título, descripción, modalidad, país y rango salarial. Si los datos son"
              + " iguales a los guardados no cambia nada. No aplica a archivadas. "
              + WHOLE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Vacante actualizada.")
  @JobWriteResponses
  public JobPostingResponse updatePosting(
      Principal principal, @PathVariable String id, @RequestBody JobRequests.Posting body) {
    return JobPostingResponse.from(
        update.execute(
            new UpdateJobPostingCommand(
                caller(principal), postingId(id), version(body.version()), input(body))));
  }

  @PutMapping(ONE + "/requirements")
  @Operation(
      summary = "Reemplazar los requisitos de una vacante propia",
      description =
          "Envía el conjunto completo que debe quedar (hasta 30). Una vacante ya publicada no puede"
              + " quedarse sin una habilidad o formación (BR-JOB-02). "
              + WHOLE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Requisitos reemplazados.")
  @JobWriteResponses
  public JobPostingResponse replaceRequirements(
      Principal principal, @PathVariable String id, @RequestBody JobRequests.Requirements body) {
    List<JobRequirementDraft> drafts =
        body.requirements() == null
            ? List.of()
            : body.requirements().stream().map(JobPostingController::draft).toList();
    return JobPostingResponse.from(
        requirements.execute(
            new ManageJobRequirementsCommand(
                caller(principal), postingId(id), version(body.version()), drafts)));
  }

  @PostMapping(ONE + "/transitions")
  @Operation(
      summary = "Cambiar el estado de una vacante propia",
      description =
          "`DRAFT → PUBLISHED`, `DRAFT → DELETED`, `PUBLISHED → CLOSED`, `CLOSED → PUBLISHED` y"
              + " `ARCHIVED → DELETED`. Archivar es automático y no se pide (BR-JOB-14); un par que"
              + " no existe responde `409 INVALID_TRANSITION`. Publicar sin una habilidad o"
              + " formación responde `400`. "
              + WHOLE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Transición ejecutada.")
  @JobWriteResponses
  public JobPostingResponse transition(
      Principal principal, @PathVariable String id, @RequestBody JobRequests.Transition body) {
    return JobPostingResponse.from(
        transitions.execute(
            new TransitionJobPostingCommand(
                caller(principal),
                postingId(id),
                required("to", body.to()),
                version(body.version()))));
  }

  @PutMapping(ONE + "/accepting-applications")
  @Operation(
      summary = "Pausar o reanudar las postulaciones",
      description =
          "Independiente del estado: la vacante sigue visible. Solo con la vacante publicada"
              + " (`409 INVALID_STATE` en otro caso, BR-JOB-10). "
              + WHOLE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Postulaciones actualizadas.")
  @JobWriteResponses
  public JobPostingResponse setAcceptingApplications(
      Principal principal,
      @PathVariable String id,
      @RequestBody JobRequests.AcceptingApplications body) {
    return JobPostingResponse.from(
        accepting.execute(
            new SetAcceptingApplicationsCommand(
                caller(principal),
                postingId(id),
                required("accepting", body.accepting()),
                version(body.version()))));
  }

  @PutMapping(ONE + "/recruiting-stage")
  @Operation(
      summary = "Cambiar la etapa de reclutamiento",
      description =
          "`REVIEWING` pausa la sugerencia proactiva de candidatos sin cerrar la vacante"
              + " (BR-MATCH-08). Solo con la vacante publicada. "
              + WHOLE,
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Etapa actualizada.")
  @JobWriteResponses
  public JobPostingResponse setRecruitingStage(
      Principal principal, @PathVariable String id, @RequestBody JobRequests.Stage body) {
    return JobPostingResponse.from(
        stage.execute(
            new SetRecruitingStageCommand(
                caller(principal),
                postingId(id),
                required("stage", body.stage()),
                version(body.version()))));
  }

  private static JobDetailsInput input(JobRequests.Posting body) {
    return new JobDetailsInput(
        body.title(),
        body.description(),
        body.modality(),
        body.country(),
        body.salaryMin(),
        body.salaryMax(),
        body.salaryCurrency(),
        body.salaryPeriod());
  }

  private static JobRequirementDraft draft(JobRequests.Requirement r) {
    return r == null
        ? null
        : new JobRequirementDraft(
            r.type(),
            r.mandatory(),
            r.skillName(),
            r.minLevel(),
            r.educationLevel(),
            r.minYears(),
            r.language(),
            r.minProficiency());
  }
}
