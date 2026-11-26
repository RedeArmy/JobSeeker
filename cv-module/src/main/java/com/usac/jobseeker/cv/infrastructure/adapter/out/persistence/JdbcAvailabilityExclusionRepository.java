package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.AvailabilityExclusionLookupPort;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AvailabilityExclusionStorePort;
import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `candidate_availability_exclusions` (`data-model.md` §4.10). La unicidad por
 * cuenta de la base de datos es el control de concurrencia de la lista: dos altas simultáneas de la
 * misma cuenta no la duplican y la perdedora se entera por el conteo de filas.
 */
public class JdbcAvailabilityExclusionRepository
    implements AvailabilityExclusionStorePort, AvailabilityExclusionLookupPort {

  private static final String PROFILE = "profile";

  private final JdbcClient jdbc;

  public JdbcAvailabilityExclusionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AvailabilityExclusion> list(CandidateProfileId profileId) {
    // Más reciente primero, con la cuenta como desempate para que el orden sea estable.
    return jdbc.sql(
            "select excluded_account_id, created_at from candidate_availability_exclusions"
                + " where candidate_profile_id = :profile order by created_at desc, excluded_account_id")
        .param(PROFILE, profileId.value())
        .query(
            (rows, n) ->
                new AvailabilityExclusion(
                    new AccountId(rows.getObject("excluded_account_id", UUID.class)),
                    rows.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  @Override
  public int count(CandidateProfileId profileId) {
    return jdbc.sql(
            "select count(*) from candidate_availability_exclusions where candidate_profile_id = :profile")
        .param(PROFILE, profileId.value())
        .query(Integer.class)
        .single();
  }

  @Override
  public boolean insert(CandidateProfileId profileId, AvailabilityExclusion exclusion) {
    return jdbc.sql(
                "insert into candidate_availability_exclusions"
                    + " (id, candidate_profile_id, excluded_account_id, created_at)"
                    + " values (:id, :profile, :excluded, :createdAt)"
                    + " on conflict (candidate_profile_id, excluded_account_id) do nothing")
            .param("id", UUID.randomUUID())
            .param(PROFILE, profileId.value())
            .param("excluded", exclusion.excludedAccount().value())
            .param("createdAt", OffsetDateTime.ofInstant(exclusion.createdAt(), ZoneOffset.UTC))
            .update()
        == 1;
  }

  @Override
  public boolean delete(CandidateProfileId profileId, AccountId excludedAccount) {
    return jdbc.sql(
                "delete from candidate_availability_exclusions"
                    + " where candidate_profile_id = :profile and excluded_account_id = :excluded")
            .param(PROFILE, profileId.value())
            .param("excluded", excludedAccount.value())
            .update()
        == 1;
  }

  @Override
  public boolean isExcluded(CandidateProfileId profileId, AccountId account) {
    return !excludedAmong(java.util.List.of(profileId), account).isEmpty();
  }

  // Una consulta para varios candidatos (TD-039): cuáles de ellos excluyeron a esta cuenta.
  @Override
  public java.util.Set<CandidateProfileId> excludedAmong(
      java.util.Collection<CandidateProfileId> profileIds, AccountId account) {
    if (profileIds.isEmpty()) {
      return java.util.Set.of();
    }
    return new java.util.HashSet<>(
        jdbc.sql(
                "select candidate_profile_id from candidate_availability_exclusions"
                    + " where candidate_profile_id = any(:profiles) and excluded_account_id = :account")
            .param(
                "profiles",
                profileIds.stream().map(CandidateProfileId::value).toArray(java.util.UUID[]::new))
            .param("account", account.value())
            .query((rows, n) -> new CandidateProfileId(rows.getObject(1, java.util.UUID.class)))
            .list());
  }
}
