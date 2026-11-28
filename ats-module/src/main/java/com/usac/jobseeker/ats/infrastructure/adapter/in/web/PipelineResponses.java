package com.usac.jobseeker.ats.infrastructure.adapter.in.web;

import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import com.usac.jobseeker.ats.application.usecase.getapplication.ApplicationDetail;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.PipelineItem;
import com.usac.jobseeker.ats.application.usecase.listjobapplications.PipelinePage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Los contratos del pipeline del reclutador. El porcentaje se redondea como en `matching` y va
 * siempre con `calibrated`: mientras sea falso es preliminar y no una probabilidad (ADR-0030).
 */
interface PipelineResponses {

  /** Una página del pipeline (`api-contract-overview.md` §8). */
  @Schema(name = "PipelinePage")
  record Page(
      List<Item> content,
      int page,
      int size,
      long totalElements,
      @Schema(
              description =
                  "La vacante tiene más postulaciones que el tope (1 000): solo se ordenaron las"
                      + " primeras.")
          boolean truncated) {

    static Page from(PipelinePage page) {
      return new Page(
          page.content().stream().map(Item::from).toList(),
          page.page(),
          page.size(),
          page.totalElements(),
          page.truncated());
    }
  }

  /** Una tarjeta del pipeline, sin contacto. */
  @Schema(name = "PipelineItem")
  record Item(
      UUID id,
      Person candidate,
      @Schema(allowableValues = {"ACTIVE", "HIRED", "REJECTED", "WITHDRAWN"}) String status,
      @Schema(allowableValues = {"NEW", "REVIEW", "INTERVIEW", "OFFER"}) String stage,
      @Schema(
              description =
                  "Motivo interno de un descarte; nunca lo ve el candidato (BR-TRACK-03).",
              allowableValues = {"NOT_A_FIT", "POSITION_FILLED", "CANDIDATE_UNRESPONSIVE", "OTHER"})
          String rejectionReason,
      long version,
      Instant appliedAt,
      Instant updatedAt,
      Summary score) {

    static Item from(PipelineItem item) {
      return new Item(
          item.applicationId(),
          Person.from(item.candidate()),
          item.status(),
          item.stage(),
          item.rejectionReason(),
          item.version(),
          item.appliedAt(),
          item.updatedAt(),
          Summary.from(item.score()));
    }
  }

  /** El candidato en una tarjeta: sin contacto. */
  @Schema(name = "PipelineCandidate")
  record Person(
      String fullName,
      String headline,
      @Schema(description = "Pidió ser olvidado: el nombre es un marcador (BR-DATA-05).")
          boolean anonymized) {

    static Person from(ApplicantProfilesPort.Profile profile) {
      return profile == null
          ? null
          : new Person(profile.fullName(), profile.headline(), profile.anonymized());
    }
  }

  /** El puntaje en una tarjeta. */
  @Schema(name = "PipelineScore")
  record Summary(
      @Schema(description = "`OK`, o `PENDING` si aún falta un vector.") String status,
      @Schema(description = "De 0 a 100; nulo si está pendiente.") Integer overall,
      @Schema(description = "Falso mientras el puntaje sea preliminar (ADR-0030).")
          boolean calibrated,
      @Schema(description = "El puntaje al postular; nulo si entonces estaba pendiente.")
          Integer appliedOverall) {

    static Summary from(ApplicantScoresPort.Score score) {
      return new Summary(
          score.status(),
          rounded(score.overall()),
          score.calibrated(),
          rounded(score.appliedOverall()));
    }
  }

  /** El detalle para el reclutador, con contacto, desglose e historial. */
  @Schema(name = "ApplicationDetail")
  record Detail(
      UUID id,
      UUID jobPostingId,
      Contact candidate,
      String status,
      String stage,
      String rejectionReason,
      long version,
      Instant appliedAt,
      Instant updatedAt,
      Score score,
      List<Change> history) {

    static Detail from(ApplicationDetail d) {
      return new Detail(
          d.applicationId(),
          d.jobPostingId(),
          Contact.from(d.candidate()),
          d.status(),
          d.stage(),
          d.rejectionReason(),
          d.version(),
          d.appliedAt(),
          d.updatedAt(),
          Score.from(d.score()),
          d.history().stream()
              .map(
                  c ->
                      new Change(
                          c.fromStatus(),
                          c.fromStage(),
                          c.toStatus(),
                          c.toStage(),
                          c.by(),
                          c.rejectionReason(),
                          c.changedAt()))
              .toList());
    }
  }

  /** El candidato con su contacto: al postular deja de estar enmascarado (REQ-C-07). */
  @Schema(name = "ApplicantContact")
  record Contact(
      String fullName,
      String headline,
      String contactEmail,
      String contactPhone,
      boolean anonymized) {

    static Contact from(ApplicantProfilesPort.Profile profile) {
      return profile == null
          ? null
          : new Contact(
              profile.fullName(),
              profile.headline(),
              profile.contactEmail(),
              profile.contactPhone(),
              profile.anonymized());
    }
  }

  /** El puntaje con su desglose (BR-MATCH-02). */
  @Schema(name = "ApplicantScore")
  record Score(
      String status,
      Integer overall,
      boolean calibrated,
      String algorithmVersion,
      Integer appliedOverall,
      Breakdown breakdown) {

    static Score from(ApplicantScoresPort.Score score) {
      ApplicantScoresPort.Breakdown b = score.breakdown();
      return new Score(
          score.status(),
          rounded(score.overall()),
          score.calibrated(),
          score.algorithmVersion(),
          rounded(score.appliedOverall()),
          b == null
              ? null
              : new Breakdown(
                  b.completeness(),
                  b.alpha(),
                  b.structured(),
                  b.semantic(),
                  b.dimensions().stream()
                      .map(
                          d ->
                              new Dimension(
                                  d.dimension(),
                                  d.status(),
                                  d.score(),
                                  d.weight(),
                                  d.explanation()))
                      .toList()));
    }
  }

  @Schema(name = "ApplicantScoreBreakdown")
  record Breakdown(
      double completeness,
      double alpha,
      double structured,
      double semantic,
      List<Dimension> dimensions) {}

  @Schema(name = "ApplicantScoreDimension")
  record Dimension(
      String dimension, String status, double score, double weight, String explanation) {}

  /** Un cambio del historial, con su motivo si fue un descarte. */
  @Schema(name = "ApplicationDetailChange")
  record Change(
      String fromStatus,
      String fromStage,
      String toStatus,
      String toStage,
      String by,
      String rejectionReason,
      Instant changedAt) {}

  private static Integer rounded(Double value) {
    return value == null ? null : (int) Math.round(value);
  }
}
