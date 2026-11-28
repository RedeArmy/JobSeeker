package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.ApplicantProfile;
import com.usac.jobseeker.cv.application.usecase.getapplicantprofiles.ApplicantProfileReadPort;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lectura de presentación de los postulantes (REQ-C-07): nombre y titular del perfil profesional y
 * contacto del de candidato, en una consulta. A diferencia de los repositorios de perfil, **no**
 * excluye las filas anonimizadas: el reclutador sigue viendo la postulación, con el marcador.
 */
public class JdbcApplicantProfileReader implements ApplicantProfileReadPort {

  private final JdbcClient jdbc;

  public JdbcApplicantProfileReader(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ApplicantProfile> findApplicants(Collection<AccountId> accounts) {
    if (accounts.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "select p.account_id, p.full_name, p.headline, c.contact_email, c.contact_phone,"
                + " (p.anonymized_at is not null or c.anonymized_at is not null) as anonymized"
                + " from professional_profiles p"
                + " left join candidate_profiles c on c.account_id = p.account_id"
                + " where p.account_id = any(:accounts)")
        .param("accounts", accounts.stream().map(AccountId::value).toArray(UUID[]::new))
        .query(
            (rows, n) ->
                new ApplicantProfile(
                    rows.getObject("account_id", UUID.class),
                    rows.getString("full_name"),
                    rows.getString("headline"),
                    rows.getString("contact_email"),
                    rows.getString("contact_phone"),
                    rows.getBoolean("anonymized")))
        .list();
  }
}
