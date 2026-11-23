package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

/** Qué hizo JobSeeker con un webhook. En todos los casos se responde 2xx a Clavaris. */
public enum HandleClavarisWebhookResult {
  /** El efecto se aplicó. */
  APPLIED,
  /** Ya se había procesado: no se vuelve a aplicar. */
  DUPLICATE,
  /** El tipo de evento no tiene efecto en JobSeeker. */
  IGNORED,
  /** La cuenta no existe en JobSeeker: nadie inició sesión, no hay nada que cerrar. */
  UNKNOWN_ACCOUNT
}
