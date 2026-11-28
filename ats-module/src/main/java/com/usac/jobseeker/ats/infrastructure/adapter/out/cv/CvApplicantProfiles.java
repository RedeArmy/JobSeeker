package com.usac.jobseeker.ats.infrastructure.adapter.out.cv;

import com.usac.jobseeker.ats.application.support.ApplicantProfilesPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.ApplicantProfile;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.GetApplicantProfilesQuery;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.GetApplicantProfilesUseCase;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Lee los datos de los postulantes con el caso de uso público de `cv` (ARCH-04), en lote. */
@Component
public class CvApplicantProfiles implements ApplicantProfilesPort {

  private final GetApplicantProfilesUseCase profiles;

  public CvApplicantProfiles(GetApplicantProfilesUseCase profiles) {
    this.profiles = profiles;
  }

  @Override
  public Map<AccountId, Profile> of(Collection<AccountId> candidates) {
    Map<AccountId, Profile> found = new HashMap<>();
    for (ApplicantProfile p :
        profiles.execute(new GetApplicantProfilesQuery(List.copyOf(candidates)))) {
      found.put(
          new AccountId(p.accountId()),
          new Profile(
              p.fullName(), p.headline(), p.contactEmail(), p.contactPhone(), p.anonymized()));
    }
    return found;
  }
}
