package com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import java.util.List;
import java.util.Objects;

/**
 * Reemplazar entero el conjunto de autorizaciones de trabajo de la propia cuenta: el identificador
 * sale del token, nunca del cuerpo. Reemplazar por un conjunto vacío lo vacía. La versión es la del
 * perfil de candidato que el cliente leyó; si otra edición se guardó antes, se rechaza en vez de
 * pisarla.
 */
public record ReplaceWorkAuthorizationsCommand(
    AccountId accountId, long expectedVersion, List<String> countries) {

  public ReplaceWorkAuthorizationsCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(countries, "countries");
    CandidateChange.requireValidVersion(expectedVersion);
    countries = List.copyOf(countries);
  }
}
