package com.usac.jobseeker.matching.domain.model;

import java.util.List;

/**
 * Lo que un reclutador ve de un candidato sugerido **antes de que postule o acepte** (REQ-C-07):
 * solo datos de contexto profesional, sin nombre, foto, contacto, enlaces ni nombres de empresas o
 * instituciones. El contacto se entrega después, bajo solicitud, y no es parte de esta tarjeta.
 *
 * @param headline el titular, con los datos de contacto que pudiera contener ya redactados
 * @param country el país de residencia, o nulo si no lo declaró
 * @param yearsOfExperience años acreditados (unión de periodos), con un decimal
 * @param highestEducation el nivel de formación terminado más alto, o nulo
 * @param topSkills hasta cinco habilidades, por nivel declarado y luego por nombre
 */
public record CandidateCard(
    String headline,
    String country,
    double yearsOfExperience,
    String highestEducation,
    List<String> topSkills) {

  public CandidateCard {
    topSkills = List.copyOf(topSkills);
  }
}
