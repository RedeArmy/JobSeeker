package com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages;

import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La capacidad, la versión,
 * el evento, la métrica y el log los pone {@link CandidateChange}, igual para los tres conjuntos;
 * aquí solo queda lo propio de idiomas: validar el conjunto y escribirlo.
 */
public class ReplaceCandidateLanguagesService implements ReplaceCandidateLanguagesUseCase {

  private static final String USE_CASE = "replace_candidate_languages";

  private final CandidateLanguagesStorePort items;
  private final CandidateChange change;

  public ReplaceCandidateLanguagesService(
      CandidateLanguagesStorePort items, CandidateChange change) {
    this.items = Objects.requireNonNull(items, "items");
    this.change = Objects.requireNonNull(change, "change");
  }

  @Override
  @Transactional
  public CandidateProfileView execute(ReplaceCandidateLanguagesCommand c) {
    // La capacidad se comprueba antes que los datos: quien no puede actuar no debe saber qué
    // reglas incumpliría (BR-ACCT-04).
    change.requireCandidate(USE_CASE, c.accountId());
    List<CandidateLanguage> clean =
        change.validated(USE_CASE, () -> CandidateLanguage.setOf(c.languages()));
    return change.apply(
        USE_CASE,
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_LANGUAGES,
        stored -> new HashSet<>(stored.languages()).equals(new HashSet<>(clean)),
        profile -> items.replaceLanguages(profile, clean));
  }
}
