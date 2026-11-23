package com.usac.jobseeker.auth.application.usecase.getmyaccount;

import java.util.Optional;

/** Devuelve a quien llama su propia cuenta (`GET /api/v1/me`). */
public interface GetMyAccountUseCase {

  /** La cuenta, o vacío si no existe en la base local. */
  Optional<MyAccountView> execute(GetMyAccountQuery query);
}
