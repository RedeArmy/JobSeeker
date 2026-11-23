package com.usac.jobseeker.auth.application.usecase.handleclavariswebhook;

/** Aplica un webhook de Clavaris ya verificado (ADR-0020, superficie C). */
public interface HandleClavarisWebhookUseCase {

  HandleClavarisWebhookResult execute(HandleClavarisWebhookCommand command);
}
