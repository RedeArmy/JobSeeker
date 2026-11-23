package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.common.domain.model.AccountId;

/** El doble en memoria cumple el mismo contrato que el almacén real. */
class InMemoryProfessionalProfilesContractTest extends ProfessionalProfileStoreContract {

  private final InMemoryProfessionalProfiles profiles = new InMemoryProfessionalProfiles();

  @Override
  protected ProfessionalProfileStorePort store() {
    return profiles;
  }

  @Override
  protected void accountExists(AccountId account) {
    // En memoria no hay restricción de clave foránea que satisfacer.
  }
}
