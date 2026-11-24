package com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations;

import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.util.List;

/**
 * Puerto de salida de la escritura de autorizaciones de trabajo. Reemplaza el conjunto **entero**
 * del perfil dado: lo anterior se borra y lo nuevo se escribe, dentro de la misma transacción del
 * caso de uso.
 */
public interface WorkAuthorizationsStorePort {

  void replaceWorkAuthorizations(CandidateProfileId profileId, List<String> items);
}
