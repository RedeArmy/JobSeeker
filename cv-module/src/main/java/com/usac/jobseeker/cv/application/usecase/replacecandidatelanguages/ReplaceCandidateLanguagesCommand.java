package com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import java.util.List;
import java.util.Objects;

/**
 * Reemplazar entero el conjunto de idiomas de la propia cuenta: el identificador sale del token,
 * nunca del cuerpo. Reemplazar por un conjunto vacío lo vacía. La versión es la del perfil de
 * candidato que el cliente leyó; si otra edición se guardó antes, se rechaza en vez de pisarla.
 */
public record ReplaceCandidateLanguagesCommand(
    AccountId accountId, long expectedVersion, List<CandidateLanguage> languages) {

  public ReplaceCandidateLanguagesCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(languages, "languages");
    CandidateChange.requireValidVersion(expectedVersion);
    languages = List.copyOf(languages);
  }
}
