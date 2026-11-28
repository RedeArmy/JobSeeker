package com.usac.jobseeker.ats.application.support;

import com.usac.jobseeker.ats.domain.model.Application;
import com.usac.jobseeker.ats.domain.model.ApplicationId;
import com.usac.jobseeker.ats.domain.model.ApplicationStatus;
import com.usac.jobseeker.ats.domain.model.StatusChange;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Almacén de postulaciones en memoria para las pruebas de los casos de uso. Imita las reglas que la
 * base impone (una sola sin retirar por candidato y vacante, compare-and-set por versión), así un
 * caso de uso que las olvide falla aquí también.
 */
public final class InMemoryApplications implements ApplicationStorePort {

  public final Map<ApplicationId, Application> rows = new LinkedHashMap<>();
  public final List<StatusChange> history = new ArrayList<>();

  /** Simula que otro cambio se guardó entre leer y escribir. */
  public boolean loseTheRace;

  /** Guarda una postulación ya hecha, con su historial, como si viniera de la base. */
  public Application put(Application.Transition transition) {
    rows.put(transition.application().id(), transition.application());
    history.add(transition.change());
    return transition.application();
  }

  @Override
  public boolean insertIfNoneOpen(Application application, StatusChange change) {
    boolean open =
        rows.values().stream()
            .anyMatch(
                a ->
                    a.candidateProfileId().equals(application.candidateProfileId())
                        && a.jobPostingId().equals(application.jobPostingId())
                        && a.status() != ApplicationStatus.WITHDRAWN);
    if (open) {
      return false;
    }
    rows.put(application.id(), application);
    history.add(change);
    return true;
  }

  @Override
  public boolean updateIfVersion(
      Application application, StatusChange change, long expectedVersion) {
    Application stored = rows.get(application.id());
    if (loseTheRace || stored == null || stored.version() != expectedVersion) {
      return false;
    }
    rows.put(application.id(), application);
    history.add(change);
    return true;
  }

  @Override
  public boolean hasOpenApplication(UUID candidateProfileId, UUID jobPostingId) {
    return rows.values().stream()
        .anyMatch(
            a ->
                a.candidateProfileId().equals(candidateProfileId)
                    && a.jobPostingId().equals(jobPostingId)
                    && a.status() != ApplicationStatus.WITHDRAWN);
  }

  @Override
  public Optional<Application> findById(ApplicationId id) {
    return Optional.ofNullable(rows.get(id));
  }

  @Override
  public List<Application> findByCandidate(AccountId candidate) {
    return rows.values().stream()
        .filter(a -> a.candidateAccountId().equals(candidate))
        .sorted(Comparator.comparing(Application::appliedAt).reversed())
        .toList();
  }

  @Override
  public List<Application> findByJob(UUID jobPostingId, int limit) {
    return rows.values().stream()
        .filter(a -> a.jobPostingId().equals(jobPostingId))
        .sorted(Comparator.comparing(Application::appliedAt))
        .limit(limit)
        .toList();
  }

  @Override
  public Map<ApplicationId, List<StatusChange>> historyOf(Collection<ApplicationId> ids) {
    Map<ApplicationId, List<StatusChange>> found = new HashMap<>();
    for (StatusChange change : history) {
      if (ids.contains(change.applicationId())) {
        found.computeIfAbsent(change.applicationId(), k -> new ArrayList<>()).add(change);
      }
    }
    return found;
  }
}
