package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileReadPort;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Almacén en memoria con la misma semántica condicional que el adaptador JDBC. */
public class InMemoryProfessionalProfiles
    implements ProfessionalProfileReadPort, ProfessionalProfileStorePort {

  private final Map<AccountId, ProfessionalProfile> byAccount = new HashMap<>();

  @Override
  public Optional<ProfessionalProfile> findByAccountId(AccountId accountId) {
    return Optional.ofNullable(byAccount.get(accountId));
  }

  @Override
  public boolean insertIfAbsent(ProfessionalProfile profile) {
    return byAccount.putIfAbsent(profile.accountId(), profile) == null;
  }

  @Override
  public boolean updateIfVersion(ProfessionalProfile profile, long expectedVersion) {
    ProfessionalProfile stored = byAccount.get(profile.accountId());
    if (stored == null || stored.version() != expectedVersion) {
      return false;
    }
    byAccount.put(profile.accountId(), profile);
    return true;
  }

  /** Una copia del estado, para que una prueba pueda simular la reversión de una transacción. */
  public Map<AccountId, ProfessionalProfile> snapshot() {
    return new HashMap<>(byAccount);
  }

  public void restore(Map<AccountId, ProfessionalProfile> state) {
    byAccount.clear();
    byAccount.putAll(state);
  }

  public int size() {
    return byAccount.size();
  }
}
