package com.usac.jobseeker.matching.infrastructure.adapter.out.cv;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotQuery;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotUseCase;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotsQuery;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotsUseCase;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.ProfileSnapshot;
import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Capa anticorrupción hacia `cv` (ARCH-04): convierte la lectura pública de `cv` en la proyección
 * propia de `matching`. Es el único lugar de `matching` que nombra un tipo de `cv`, y lo que le
 * llega ya viene sin nombre, foto, contacto, enlaces ni nombres de empresas, instituciones o
 * emisores: `cv` lo garantiza en su origen y la forma de {@link CandidateSignals} no tiene dónde
 * guardarlos.
 *
 * <p>Se registra a sí mismo como componente porque es el único lugar donde `matching` puede nombrar
 * un tipo de `cv`: la configuración (`infrastructure.config`) solo ve el puerto propio.
 */
@Component
public class CvCandidateSignalsReader implements CandidateSignalsReader {

  private final GetProfileSnapshotUseCase snapshots;
  private final GetProfileSnapshotsUseCase batch;

  public CvCandidateSignalsReader(
      GetProfileSnapshotUseCase snapshots, GetProfileSnapshotsUseCase batch) {
    this.snapshots = snapshots;
    this.batch = batch;
  }

  @Override
  public Optional<CandidateSignals> read(AccountId accountId) {
    return snapshots.execute(new GetProfileSnapshotQuery(accountId)).map(this::translate);
  }

  // Todas las cuentas con pocas consultas (TD-039), con las mismas reglas que la lectura
  // individual.
  @Override
  public Map<AccountId, CandidateSignals> readAll(Collection<AccountId> accountIds) {
    Map<AccountId, CandidateSignals> found = new HashMap<>();
    for (int from = 0; from < accountIds.size(); from += GetProfileSnapshotsQuery.MAX_ACCOUNTS) {
      List<AccountId> chunk =
          accountIds.stream().skip(from).limit(GetProfileSnapshotsQuery.MAX_ACCOUNTS).toList();
      for (ProfileSnapshot snapshot : batch.execute(new GetProfileSnapshotsQuery(chunk))) {
        found.put(new AccountId(snapshot.accountId()), translate(snapshot));
      }
    }
    return found;
  }

  private CandidateSignals translate(ProfileSnapshot s) {
    return new CandidateSignals(
        new CandidateProfileId(s.candidateProfileId()),
        new AccountId(s.accountId()),
        s.discoverable(),
        s.headline(),
        s.countryOfResidence(),
        s.desiredModality(),
        s.preferredWorkSchedule(),
        s.relocationWillingness(),
        s.salary() == null
            ? null
            : new CandidateSignals.Salary(
                s.salary().min(), s.salary().max(), s.salary().currency(), s.salary().period()),
        s.experiences().stream()
            .map(
                e ->
                    new CandidateSignals.Experience(
                        e.title(), e.startDate(), e.endDate(), e.description()))
            .toList(),
        s.education().stream()
            .map(e -> new CandidateSignals.Education(e.degree(), e.level(), e.completionDate()))
            .toList(),
        s.certifications().stream().map(ProfileSnapshot.Certification::name).toList(),
        s.projects().stream()
            .map(p -> new CandidateSignals.Project(p.name(), p.description(), p.type(), p.role()))
            .toList(),
        s.skills().stream().map(k -> new CandidateSignals.Skill(k.name(), k.level())).toList(),
        s.languages().stream()
            .map(l -> new CandidateSignals.Language(l.language(), l.proficiency()))
            .toList(),
        s.workAuthorizations());
  }
}
