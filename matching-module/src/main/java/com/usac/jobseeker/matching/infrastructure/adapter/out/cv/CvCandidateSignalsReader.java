package com.usac.jobseeker.matching.infrastructure.adapter.out.cv;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotQuery;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotUseCase;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.ProfileSnapshot;
import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
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

  public CvCandidateSignalsReader(GetProfileSnapshotUseCase snapshots) {
    this.snapshots = snapshots;
  }

  @Override
  public Optional<CandidateSignals> read(AccountId accountId) {
    return snapshots.execute(new GetProfileSnapshotQuery(accountId)).map(this::translate);
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
