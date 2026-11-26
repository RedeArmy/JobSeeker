package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Puerto de salida: qué cuenta es dueña de cada perfil dentro de `matching`. Los puntajes se
 * guardan por perfil y las señales se leen por cuenta; este puerto las une sin que `matching` lea
 * tablas de `cv`. Una cuenta anonimizada no aparece.
 */
public interface ProfileDirectoryPort {

  Optional<AccountId> accountOf(CandidateProfileId profile);

  Map<CandidateProfileId, AccountId> accountsOf(Collection<CandidateProfileId> profiles);

  Optional<CandidateProfileId> profileOf(AccountId account);
}
