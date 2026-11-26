package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfileCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.SalaryExpectation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Es la **única** puerta por
 * la que un dato del perfil sale de `cv`: lo que no se copia aquí no puede llegar a ningún
 * consumidor. Por eso cada campo se copia a mano y no se reutiliza ninguna vista, que podría crecer
 * con un dato personal sin que nadie lo note. Todo se lee en una sola transacción de solo lectura,
 * para que el perfil profesional y el de candidato sean de la misma versión.
 */
public class GetProfileSnapshotService
    implements GetProfileSnapshotUseCase, GetProfileSnapshotsUseCase {

  private static final String USE_CASE = "get_profile_snapshot";

  private final CandidateCapabilityPort capabilities;
  private final ProfessionalProfileReadPort professionals;
  private final ProfileCollectionsReadPort professionalCollections;
  private final CandidateProfileReadPort candidates;
  private final CandidateCollectionsReadPort candidateCollections;
  private final UseCaseMetricsPort metrics;

  public GetProfileSnapshotService(
      CandidateCapabilityPort capabilities,
      ProfessionalProfileReadPort professionals,
      ProfileCollectionsReadPort professionalCollections,
      CandidateProfileReadPort candidates,
      CandidateCollectionsReadPort candidateCollections,
      UseCaseMetricsPort metrics) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.professionals = Objects.requireNonNull(professionals, "professionals");
    this.professionalCollections =
        Objects.requireNonNull(professionalCollections, "professionalCollections");
    this.candidates = Objects.requireNonNull(candidates, "candidates");
    this.candidateCollections =
        Objects.requireNonNull(candidateCollections, "candidateCollections");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProfileSnapshot> execute(GetProfileSnapshotQuery query) {
    Optional<ProfileSnapshot> snapshot = buildAll(List.of(query.accountId())).stream().findFirst();
    metrics.count(USE_CASE, snapshot.isPresent() ? "found" : "unavailable");
    return snapshot;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProfileSnapshot> execute(GetProfileSnapshotsQuery query) {
    List<ProfileSnapshot> snapshots = buildAll(query.accountIds());
    metrics.count(USE_CASE, "batch");
    return snapshots;
  }

  // La lectura individual es el caso de una cuenta: una sola implementación de las reglas. Sin la
  // capacidad el perfil se conserva pero deja de ser legible desde fuera (decisión 8 del TDD):
  // apagarla no debe dejar el perfil en los sugeridos. Se leen todos los perfiles con una consulta
  // por tabla y no una tanda por cuenta (TD-039).
  private List<ProfileSnapshot> buildAll(List<AccountId> accounts) {
    Set<AccountId> candidatesOnly = capabilities.candidatesAmong(accounts);
    if (candidatesOnly.isEmpty()) {
      return List.of();
    }
    Map<AccountId, ProfessionalProfile> professionalOf =
        professionals.findAllByAccountIds(candidatesOnly);
    Map<AccountId, CandidateProfile> candidateOf = candidates.findAllByAccountIds(candidatesOnly);
    Map<ProfessionalProfileId, ProfileCollections> professionalItems =
        professionalCollections.findAllByProfileIds(
            professionalOf.values().stream().map(ProfessionalProfile::id).toList());
    Map<CandidateProfileId, CandidateCollections> candidateItems =
        candidateCollections.findAllByProfileIds(
            candidateOf.values().stream().map(CandidateProfile::id).toList());
    List<ProfileSnapshot> snapshots = new ArrayList<>();
    for (AccountId account : accounts) {
      ProfessionalProfile professional = professionalOf.get(account);
      CandidateProfile candidate = candidateOf.get(account);
      if (professional == null || candidate == null || !candidatesOnly.contains(account)) {
        continue;
      }
      snapshots.add(
          project(
              professional,
              professionalItems.get(professional.id()),
              candidate,
              candidateItems.get(candidate.id())));
    }
    return snapshots;
  }

  private static ProfileSnapshot project(
      ProfessionalProfile professional,
      ProfileCollections items,
      CandidateProfile candidate,
      CandidateCollections sets) {
    CandidateDetails details = candidate.details();
    return new ProfileSnapshot(
        candidate.id().value(),
        candidate.accountId().value(),
        candidate.discoverable(),
        professional.version(),
        candidate.version(),
        professional.identity().headline(),
        details.countryOfResidence(),
        name(details.desiredModality()),
        name(details.preferredWorkSchedule()),
        details.relocationWillingness(),
        salary(details.salary()),
        items.experiences().stream()
            .map(
                e ->
                    new ProfileSnapshot.Experience(
                        e.title(), e.startDate(), e.endDate(), e.description()))
            .toList(),
        items.education().stream()
            .map(
                e -> new ProfileSnapshot.Education(e.degree(), name(e.level()), e.completionDate()))
            .toList(),
        items.certifications().stream()
            .map(
                c -> new ProfileSnapshot.Certification(c.name(), c.issueDate(), c.expirationDate()))
            .toList(),
        items.projects().stream()
            .map(
                p ->
                    new ProfileSnapshot.Project(
                        p.name(),
                        p.description(),
                        name(p.type()),
                        p.role(),
                        p.startDate(),
                        p.endDate()))
            .toList(),
        sets.skills().stream().map(s -> new ProfileSnapshot.Skill(s.name(), s.level())).toList(),
        sets.languages().stream()
            .map(l -> new ProfileSnapshot.Language(l.language(), name(l.proficiency())))
            .toList(),
        sets.workAuthorizations());
  }

  private static ProfileSnapshot.Salary salary(SalaryExpectation salary) {
    return salary == null
        ? null
        : new ProfileSnapshot.Salary(
            salary.min(), salary.max(), salary.currency(), salary.period().name());
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
