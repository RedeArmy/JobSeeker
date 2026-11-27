package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.changeavailability.AvailabilityStorePort;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.DiscoverabilityStorePort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.ContactInfo;
import com.usac.jobseeker.cv.domain.model.SalaryExpectation;
import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.domain.model.WorkSchedule;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `candidate_profiles` (`data-model.md` §4.4); implementa los dos puertos. La
 * escritura de datos NO toca `discoverable` ni `availability_status`: son de sus propios casos de
 * uso y un guardado del contacto no debe poder reabrir un perfil que su dueño ocultó.
 */
public class JdbcCandidateProfileRepository
    implements CandidateProfileReadPort,
        CandidateProfileStorePort,
        DiscoverabilityStorePort,
        AvailabilityStorePort {

  private static final String ACCOUNT = "account";
  private static final String VERSION = "version";
  private static final String DISCOVERABLE = "discoverable";
  private static final String EXPECTED = "expected";
  private static final String UPDATED_AT = "updatedAt";

  // TD-015: las columnas que se leen, una sola vez. El SQL es siempre constante (ADR-0032).
  private static final String COLUMNS =
      "id, account_id, contact_email, contact_phone, discoverable, availability_status,"
          + " country_of_residence, desired_modality, preferred_work_schedule,"
          + " relocation_willingness, salary_expectation_min, salary_expectation_max,"
          + " salary_currency, salary_period, version, updated_at";

  private final JdbcClient jdbc;

  public JdbcCandidateProfileRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CandidateProfile> findByAccountId(AccountId accountId) {
    return Optional.ofNullable(findAllByAccountIds(List.of(accountId)).get(accountId));
  }

  // Una consulta para varias cuentas (TD-039); la individual es el caso de una.
  @Override
  public Map<AccountId, CandidateProfile> findAllByAccountIds(Collection<AccountId> accountIds) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    Map<AccountId, CandidateProfile> found = new HashMap<>();
    jdbc.sql(
            "select "
                + COLUMNS
                + " from candidate_profiles where account_id = any(:accounts)"
                + " and anonymized_at is null")
        .param("accounts", accountIds.stream().map(AccountId::value).toArray(UUID[]::new))
        .query(
            (rows, n) -> {
              CandidateProfile profile = profileOf(rows);
              found.put(profile.accountId(), profile);
              return profile;
            })
        .list();
    return found;
  }

  // ON CONFLICT DO NOTHING sobre `account_id` hace atómica la creación: dos primeros guardados a la
  // vez no chocan ni crean dos perfiles; solo uno ve `1` fila afectada y el otro lo sabe.
  @Override
  public boolean insertIfAbsent(CandidateProfile profile) {
    return jdbc.sql(
                "insert into candidate_profiles (id, account_id, contact_email, contact_phone,"
                    + " discoverable, availability_status, country_of_residence, desired_modality,"
                    + " preferred_work_schedule, relocation_willingness, salary_expectation_min,"
                    + " salary_expectation_max, salary_currency, salary_period, version, updated_at)"
                    + " values (:id, :account, :email, :phone, :discoverable, :availability,"
                    + " :country, :modality, :schedule, :relocation, :salaryMin, :salaryMax,"
                    + " :currency, :period, :version, :updatedAt)"
                    + " on conflict (account_id) do nothing")
            .params(detailsParams(profile))
            .param("id", profile.id().value())
            .param(ACCOUNT, profile.accountId().value())
            .param(DISCOVERABLE, profile.discoverable())
            .param("availability", profile.availability().name())
            .update()
        == 1;
  }

  // La condición sobre `version` es el bloqueo optimista: si otra edición ya incrementó la versión,
  // no se actualiza ninguna fila y quien llama sabe que perdió la carrera. Cubre tanto la edición
  // de
  // los datos como el simple avance de versión de quien reemplaza un conjunto.
  @Override
  public boolean updateIfVersion(CandidateProfile profile, long expectedVersion) {
    return jdbc.sql(
                "update candidate_profiles set contact_email = :email, contact_phone = :phone,"
                    + " country_of_residence = :country, desired_modality = :modality,"
                    + " preferred_work_schedule = :schedule, relocation_willingness = :relocation,"
                    + " salary_expectation_min = :salaryMin, salary_expectation_max = :salaryMax,"
                    + " salary_currency = :currency, salary_period = :period, version = :version,"
                    + " updated_at = :updatedAt"
                    + " where account_id = :account and version = :expected and anonymized_at is null")
            .params(detailsParams(profile))
            .param(ACCOUNT, profile.accountId().value())
            .param(EXPECTED, expectedVersion)
            .update()
        == 1;
  }

  // Solo esta columna y la versión: un cambio de descubribilidad no debe poder pisar datos del
  // perfil
  // que otra edición acaba de guardar. La condición sobre `version` es el mismo bloqueo optimista.
  @Override
  public boolean updateDiscoverabilityIfVersion(CandidateProfile profile, long expectedVersion) {
    return jdbc.sql(
                "update candidate_profiles set discoverable = :discoverable, version = :version,"
                    + " updated_at = :updatedAt where account_id = :account and version = :expected"
                    + " and anonymized_at is null")
            .param(DISCOVERABLE, profile.discoverable())
            .param(VERSION, profile.version())
            .param(UPDATED_AT, OffsetDateTime.ofInstant(profile.updatedAt(), ZoneOffset.UTC))
            .param(ACCOUNT, profile.accountId().value())
            .param(EXPECTED, expectedVersion)
            .update()
        == 1;
  }

  @Override
  public boolean updateAvailabilityIfVersion(CandidateProfile profile, long expectedVersion) {
    return jdbc.sql(
                "update candidate_profiles set availability_status = :status, version = :version,"
                    + " updated_at = :updatedAt where account_id = :account and version = :expected"
                    + " and anonymized_at is null")
            .param("status", profile.availability().name())
            .param(VERSION, profile.version())
            .param(UPDATED_AT, OffsetDateTime.ofInstant(profile.updatedAt(), ZoneOffset.UTC))
            .param(ACCOUNT, profile.accountId().value())
            .param(EXPECTED, expectedVersion)
            .update()
        == 1;
  }

  // TD-015: los datos del perfil que comparten el alta y la edición, armados en un solo lugar. Un
  // mapa que admite nulos: un dato no declarado se guarda como NULL.
  private static Map<String, Object> detailsParams(CandidateProfile profile) {
    CandidateDetails d = profile.details();
    SalaryExpectation salary = d.salary();
    Map<String, Object> params = new HashMap<>();
    params.put("email", d.contact().email());
    params.put("phone", d.contact().phone());
    params.put("country", d.countryOfResidence());
    params.put("modality", d.desiredModality() == null ? null : d.desiredModality().name());
    params.put(
        "schedule", d.preferredWorkSchedule() == null ? null : d.preferredWorkSchedule().name());
    params.put("relocation", d.relocationWillingness());
    params.put("salaryMin", salary == null ? null : salary.min());
    params.put("salaryMax", salary == null ? null : salary.max());
    params.put("currency", salary == null ? null : salary.currency());
    params.put("period", salary == null ? null : salary.period().name());
    params.put(VERSION, profile.version());
    params.put(UPDATED_AT, OffsetDateTime.ofInstant(profile.updatedAt(), ZoneOffset.UTC));
    return params;
  }

  private static CandidateProfile profileOf(ResultSet rows) throws SQLException {
    String modality = rows.getString("desired_modality");
    String schedule = rows.getString("preferred_work_schedule");
    BigDecimal min = rows.getBigDecimal("salary_expectation_min");
    BigDecimal max = rows.getBigDecimal("salary_expectation_max");
    String period = rows.getString("salary_period");
    SalaryExpectation salary =
        period == null
            ? null
            : new SalaryExpectation(
                min, max, rows.getString("salary_currency"), SalaryPeriod.valueOf(period));
    CandidateDetails details =
        new CandidateDetails(
            new ContactInfo(rows.getString("contact_email"), rows.getString("contact_phone")),
            rows.getString("country_of_residence"),
            modality == null ? null : WorkModality.valueOf(modality),
            schedule == null ? null : WorkSchedule.valueOf(schedule),
            rows.getBoolean("relocation_willingness"),
            salary);
    return new CandidateProfile(
        new CandidateProfileId(rows.getObject("id", UUID.class)),
        new AccountId(rows.getObject("account_id", UUID.class)),
        details,
        rows.getBoolean(DISCOVERABLE),
        AvailabilityStatus.valueOf(rows.getString("availability_status")),
        rows.getLong(VERSION),
        rows.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
