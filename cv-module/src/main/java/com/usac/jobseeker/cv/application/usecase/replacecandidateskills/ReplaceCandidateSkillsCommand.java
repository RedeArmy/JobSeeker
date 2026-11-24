package com.usac.jobseeker.cv.application.usecase.replacecandidateskills;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import java.util.List;
import java.util.Objects;

/**
 * Reemplazar entero el conjunto de habilidades de la propia cuenta: el identificador sale del
 * token, nunca del cuerpo. Reemplazar por un conjunto vacío lo vacía. La versión es la del perfil
 * de candidato que el cliente leyó; si otra edición se guardó antes, se rechaza en vez de pisarla.
 */
public record ReplaceCandidateSkillsCommand(
    AccountId accountId, long expectedVersion, List<CandidateSkill> skills) {

  public ReplaceCandidateSkillsCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(skills, "skills");
    CandidateChange.requireValidVersion(expectedVersion);
    skills = List.copyOf(skills);
  }
}
