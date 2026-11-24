package com.usac.jobseeker.cv.application.usecase.replacecandidateskills;

import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import java.util.List;

/**
 * Puerto de salida de la escritura de habilidades. Reemplaza el conjunto **entero** del perfil
 * dado: lo anterior se borra y lo nuevo se escribe, dentro de la misma transacción del caso de uso.
 */
public interface CandidateSkillsStorePort {

  void replaceSkills(CandidateProfileId profileId, List<CandidateSkill> items);
}
