package com.usac.jobseeker.ats.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.InvalidTransitionException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * La postulación de un candidato a una vacante (tdd/ats-tracker-modules.md §3). Es inmutable: cada
 * movimiento devuelve una copia con la versión siguiente y la fila de historial que lo registra,
 * así el caso de uso no puede guardar un cambio sin su historial.
 *
 * <p>Solo lleva identificadores (regla 7 del TDD): ni nombre ni contacto, que siguen en `cv`.
 *
 * @param candidateProfileId el perfil de candidato de `cv`, como identificador opaco
 * @param jobPostingId la vacante de `job`, como identificador opaco
 * @param cvFileId la versión del CV con que se postuló (ADR-0021 §5)
 * @param rejectionReason solo mientras está descartada (D5)
 */
public record Application(
    ApplicationId id,
    UUID candidateProfileId,
    AccountId candidateAccountId,
    UUID jobPostingId,
    UUID cvFileId,
    ApplicationStatus status,
    PipelineStage stage,
    RejectionReason rejectionReason,
    long version,
    Instant appliedAt,
    Instant updatedAt) {

  /** La primera versión es 1, como en `job`: el compare-and-set nunca compara con un cero. */
  public static final long FIRST_VERSION = 1;

  public Application {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(candidateProfileId, "candidateProfileId");
    Objects.requireNonNull(candidateAccountId, "candidateAccountId");
    Objects.requireNonNull(jobPostingId, "jobPostingId");
    Objects.requireNonNull(cvFileId, "cvFileId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(appliedAt, "appliedAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if ((status == ApplicationStatus.REJECTED) != (rejectionReason != null)) {
      throw new IllegalArgumentException("solo una descartada tiene motivo, y toda descartada");
    }
    if (version < FIRST_VERSION) {
      throw new IllegalArgumentException("la versión empieza en 1");
    }
  }

  /** Lo que deja un movimiento: la postulación nueva y la fila de historial que lo registra. */
  public record Transition(Application application, StatusChange change) {

    public Transition {
      Objects.requireNonNull(application, "application");
      Objects.requireNonNull(change, "change");
    }
  }

  /**
   * Postular: nace en proceso, en la primera columna, y su primera fila de historial no tiene
   * origen. Que la vacante acepte postulaciones y que el candidato esté listo lo comprueba el caso
   * de uso antes, porque son datos de `job` y `cv`.
   */
  public static Transition submit(
      ApplicationId id,
      UUID candidateProfileId,
      AccountId candidateAccountId,
      UUID jobPostingId,
      UUID cvFileId,
      UUID changeId,
      Instant now) {
    Application application =
        new Application(
            id,
            candidateProfileId,
            candidateAccountId,
            jobPostingId,
            cvFileId,
            ApplicationStatus.ACTIVE,
            PipelineStage.NEW,
            null,
            FIRST_VERSION,
            now,
            now);
    StatusChange change =
        new StatusChange(
            changeId,
            id,
            null,
            null,
            ApplicationStatus.ACTIVE,
            PipelineStage.NEW,
            ChangeInitiator.CANDIDATE,
            null,
            now);
    return new Transition(application, change);
  }

  /** Mover a otra columna, hacia delante o hacia atrás: un pipeline real vuelve atrás. */
  public Transition changeStage(PipelineStage to, ChangeInitiator by, UUID changeId, Instant now) {
    Objects.requireNonNull(to, "to");
    // Quedarse en la misma columna no es un cambio: no se registra ni se notifica.
    if (to == stage) {
      throw new InvalidTransitionException();
    }
    return move(ApplicationMove.CHANGE_STAGE, by, to, null, changeId, now);
  }

  /** Descartar, siempre con un motivo del catálogo (D5, REQ-R-03). */
  public Transition reject(RejectionReason reason, ChangeInitiator by, UUID changeId, Instant now) {
    if (reason == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("reason", "REQUIRED")));
    }
    return move(ApplicationMove.REJECT, by, stage, reason, changeId, now);
  }

  /** Contratar, desde cualquier columna. */
  public Transition hire(ChangeInitiator by, UUID changeId, Instant now) {
    return move(ApplicationMove.HIRE, by, stage, null, changeId, now);
  }

  /** El candidato se retira (REQ-C-03); podrá volver a postular a la misma vacante. */
  public Transition withdraw(ChangeInitiator by, UUID changeId, Instant now) {
    return move(ApplicationMove.WITHDRAW, by, stage, null, changeId, now);
  }

  /**
   * Reabrir un descarte (D3, BR-TRACK-04): vuelve a la columna que tenía, sin el motivo, que queda
   * en el historial.
   */
  public Transition reopen(ChangeInitiator by, UUID changeId, Instant now) {
    return move(ApplicationMove.REOPEN, by, stage, null, changeId, now);
  }

  // El único punto que decide si un movimiento vale: la tabla de ApplicationMove.
  private Transition move(
      ApplicationMove move,
      ChangeInitiator by,
      PipelineStage toStage,
      RejectionReason reason,
      UUID changeId,
      Instant now) {
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(changeId, "changeId");
    Objects.requireNonNull(now, "now");
    if (!move.allows(status, by)) {
      throw new InvalidTransitionException();
    }
    ApplicationStatus toStatus = move.target();
    Application after =
        new Application(
            id,
            candidateProfileId,
            candidateAccountId,
            jobPostingId,
            cvFileId,
            toStatus,
            toStage,
            reason,
            version + 1,
            appliedAt,
            now);
    StatusChange change =
        new StatusChange(changeId, id, status, stage, toStatus, toStage, by, reason, now);
    return new Transition(after, change);
  }
}
