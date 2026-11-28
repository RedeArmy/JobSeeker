package com.usac.jobseeker.cv.application.usecase.getapplicantprofiles;

import java.util.UUID;

/**
 * Lo que el reclutador de una postulación ve del candidato: al postular deja de estar enmascarado
 * (REQ-C-07), así que aquí sí van el nombre y el contacto que `getprofilesnapshot` excluye a
 * propósito. Es un dato personal: nunca va a un log ni a una métrica (BR-DATA-01).
 *
 * @param anonymized la persona pidió ser olvidada: el nombre es el marcador y no hay contacto
 *     (BR-DATA-05); la postulación sigue en el pipeline del reclutador
 */
public record ApplicantProfile(
    UUID accountId,
    String fullName,
    String headline,
    String contactEmail,
    String contactPhone,
    boolean anonymized) {}
