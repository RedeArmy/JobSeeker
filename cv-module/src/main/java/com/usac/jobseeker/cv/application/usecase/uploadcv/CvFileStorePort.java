package com.usac.jobseeker.cv.application.usecase.uploadcv;

import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CvFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida de las versiones del CV; las versiones borradas al anonimizar no aparecen. */
public interface CvFileStorePort {

  Optional<CvFile> current(CandidateProfileId profile);

  /** Las versiones del perfil, de la más reciente a la más antigua. */
  List<CvFile> versions(CandidateProfileId profile);

  Optional<CvFile> find(CandidateProfileId profile, UUID fileId);

  /**
   * Bloquea el perfil hasta el final de la transacción (`FOR UPDATE`): dos subidas simultáneas de
   * la misma cuenta se ordenan y el tope de versiones se cumple con exactitud.
   */
  void lockProfile(CandidateProfileId profile);

  int count(CandidateProfileId profile);

  /** Da de alta la versión y la deja como la única vigente del perfil. */
  void insertCurrent(CvFile file);
}
