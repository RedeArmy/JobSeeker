package com.usac.jobseeker.ats.domain.model;

/**
 * Por qué se descartó, de un catálogo cerrado e interno (D5, REQ-R-03): un descarte sin motivo no
 * se podría auditar después. El candidato nunca lo ve; ve «No continúa» (BR-TRACK-03).
 */
public enum RejectionReason {
  NOT_A_FIT,
  POSITION_FILLED,
  /** También cubre el *ghosting*: un descarte por esto se puede reabrir (D3). */
  CANDIDATE_UNRESPONSIVE,
  OTHER
}
