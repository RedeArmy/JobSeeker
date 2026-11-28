package com.usac.jobseeker.tracker.infrastructure.adapter.out.ats;

import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.CandidateApplication;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.ListCandidateApplicationsQuery;
import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.ListCandidateApplicationsUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.tracker.application.support.CandidateApplicationsPort;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Lee las postulaciones del candidato con el caso de uso público de `ats` (ARCH-04): `tracker`
 * nunca lee `applications`. Traduce a los tipos propios de `tracker`.
 */
@Component
public class AtsCandidateApplications implements CandidateApplicationsPort {

  private final ListCandidateApplicationsUseCase list;

  public AtsCandidateApplications(ListCandidateApplicationsUseCase list) {
    this.list = list;
  }

  @Override
  public List<Tracked> of(AccountId candidate) {
    return list.execute(new ListCandidateApplicationsQuery(candidate)).stream()
        .map(AtsCandidateApplications::tracked)
        .toList();
  }

  private static Tracked tracked(CandidateApplication a) {
    return new Tracked(
        a.applicationId(),
        a.jobPostingId(),
        a.status(),
        a.stage(),
        a.version(),
        a.appliedAt(),
        a.updatedAt(),
        a.history().stream()
            .map(
                c ->
                    new Change(
                        c.fromStatus(),
                        c.fromStage(),
                        c.toStatus(),
                        c.toStage(),
                        c.by(),
                        c.changedAt()))
            .toList());
  }
}
