package com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages;

import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.util.List;

/**
 * Puerto de salida de la escritura de idiomas. Reemplaza el conjunto **entero** del perfil dado: lo
 * anterior se borra y lo nuevo se escribe, dentro de la misma transacción del caso de uso.
 */
public interface CandidateLanguagesStorePort {

  void replaceLanguages(CandidateProfileId profileId, List<CandidateLanguage> items);
}
