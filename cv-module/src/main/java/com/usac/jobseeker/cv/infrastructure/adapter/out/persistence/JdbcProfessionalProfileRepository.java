package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.ProfessionalProfileStorePort;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Adaptador JDBC de `professional_profiles` (data-model.md §4.2); implementa los dos puertos. */
public class JdbcProfessionalProfileRepository
    implements ProfessionalProfileReadPort, ProfessionalProfileStorePort {

  // Nombres que se repiten entre las consultas, como parámetro y como columna.
  private static final String ACCOUNT = "account";
  private static final String HEADLINE = "headline";
  private static final String VERSION = "version";

  private final JdbcClient jdbc;

  public JdbcProfessionalProfileRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ProfessionalProfile> findByAccountId(AccountId accountId) {
    return jdbc.sql(
            "select id, account_id, full_name, headline, bio, version, updated_at"
                + " from professional_profiles where account_id = :account and anonymized_at is null")
        .param(ACCOUNT, accountId.value())
        .query(
            (rows, n) ->
                new ProfessionalProfile(
                    new ProfessionalProfileId(rows.getObject("id", UUID.class)),
                    new AccountId(rows.getObject("account_id", UUID.class)),
                    new ProfessionalIdentity(
                        rows.getString("full_name"),
                        rows.getString(HEADLINE),
                        rows.getString("bio")),
                    rows.getLong(VERSION),
                    rows.getObject("updated_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  // ON CONFLICT DO NOTHING sobre `account_id` hace atómica la creación: dos primeros guardados a la
  // vez no chocan ni crean dos perfiles; solo uno ve `1` fila afectada y el otro lo sabe.
  @Override
  public boolean insertIfAbsent(ProfessionalProfile profile) {
    return jdbc.sql(
                "insert into professional_profiles"
                    + " (id, account_id, full_name, headline, bio, version, updated_at)"
                    + " values (:id, :account, :name, :headline, :bio, :version, :updatedAt)"
                    + " on conflict (account_id) do nothing")
            .param("id", profile.id().value())
            .param(ACCOUNT, profile.accountId().value())
            .param("name", profile.identity().fullName())
            .param(HEADLINE, profile.identity().headline())
            .param("bio", profile.identity().bio())
            .param(VERSION, profile.version())
            .param(
                "updatedAt",
                OffsetDateTime.ofInstant(profile.updatedAt(), java.time.ZoneOffset.UTC))
            .update()
        == 1;
  }

  // La condición sobre `version` es el bloqueo optimista: si otra edición ya incrementó la versión,
  // no se actualiza ninguna fila y quien llama sabe que perdió la carrera.
  @Override
  public boolean updateIfVersion(ProfessionalProfile profile, long expectedVersion) {
    return jdbc.sql(
                "update professional_profiles set full_name = :name, headline = :headline,"
                    + " bio = :bio, version = :version, updated_at = :updatedAt"
                    + " where account_id = :account and version = :expected and anonymized_at is null")
            .param("name", profile.identity().fullName())
            .param(HEADLINE, profile.identity().headline())
            .param("bio", profile.identity().bio())
            .param(VERSION, profile.version())
            .param(
                "updatedAt",
                OffsetDateTime.ofInstant(profile.updatedAt(), java.time.ZoneOffset.UTC))
            .param(ACCOUNT, profile.accountId().value())
            .param("expected", expectedVersion)
            .update()
        == 1;
  }
}
