package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.UUID;

/**
 * ¿La vacante es del reclutador? Toda acción del reclutador se autoriza por la vacante raíz de la
 * postulación (regla 1 del TDD). Una vacante ajena, eliminada o inexistente da lo mismo: `false`, y
 * la postulación no existe para él (`404`, BR-VIS-03).
 */
public interface OwnedJobPort {
  boolean owns(AccountId recruiter, UUID jobPostingId);
}
