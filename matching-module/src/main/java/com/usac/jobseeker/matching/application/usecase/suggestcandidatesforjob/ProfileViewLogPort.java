package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Puerto de salida del registro de perfiles vistos (`profile_view_log`, REQ-R-11): lo que cuenta la
 * cuota y lo que deja reconstruir qué vio quién. Se usa dentro de la transacción del caso de uso.
 */
public interface ProfileViewLogPort {

  /**
   * Pone en fila a los pedidos de un mismo reclutador hasta que termine la transacción: sin esto,
   * dos pedidos simultáneos contarían ambos «lo que queda» y pasarían juntos de la cuota. Es por
   * reclutador, así que no frena a los demás.
   */
  void lock(AccountId viewer);

  /** Los perfiles únicos que el reclutador vio desde `since`. */
  Set<CandidateProfileId> viewedSince(AccountId viewer, Instant since);

  /** Deja constancia de cada perfil mostrado, uno por aparición. */
  void record(AccountId viewer, JobPostingId job, List<CandidateProfileId> shown, Instant at);
}
