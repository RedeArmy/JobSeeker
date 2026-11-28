package com.usac.jobseeker.ats.application.usecase.listjobapplications;

import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.ats.application.support.ApplicantScoresPort;
import java.time.Instant;
import java.util.UUID;

/**
 * Una tarjeta del pipeline: quién, en qué estado y columna, y su puntaje. Sin contacto: ese está en
 * el detalle (`getapplication`). El reclutador sí ve el motivo de un descarte (BR-TRACK-03).
 *
 * @param candidate nulo solo si `cv` ya no tiene el perfil, lo que no debería ocurrir
 */
public record PipelineItem(
    UUID applicationId,
    ApplicantProfilesPort.Profile candidate,
    String status,
    String stage,
    String rejectionReason,
    long version,
    Instant appliedAt,
    Instant updatedAt,
    ApplicantScoresPort.Score score) {}
