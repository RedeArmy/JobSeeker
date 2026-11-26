package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.ProfilePhotoStorePort;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de la foto del perfil profesional (`tdd/cv-module.md` §10.8). Solo toca las dos
 * columnas de la foto: no cambia la versión del perfil ni emite eventos de perfil, porque la foto
 * no la lee ningún otro módulo.
 */
public class JdbcProfilePhotoRepository implements ProfilePhotoStorePort {

  private static final String SELECT =
      "select id, version, profile_photo_key, profile_photo_type from professional_profiles"
          + " where account_id = :account and anonymized_at is null";

  private final JdbcClient jdbc;

  public JdbcProfilePhotoRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PhotoSlot> find(AccountId account) {
    return jdbc.sql(SELECT)
        .param("account", account.value())
        .query(JdbcProfilePhotoRepository::slotOf)
        .optional();
  }

  @Override
  public Optional<PhotoSlot> lock(AccountId account) {
    return jdbc.sql(SELECT + " for update")
        .param("account", account.value())
        .query(JdbcProfilePhotoRepository::slotOf)
        .optional();
  }

  @Override
  public void set(ProfessionalProfileId profile, String key, FileType type) {
    jdbc.sql(
            "update professional_profiles set profile_photo_key = :key, profile_photo_type = :type"
                + " where id = :id")
        .param("key", key)
        .param("type", type == null ? null : type.name())
        .param("id", profile.value())
        .update();
  }

  private static PhotoSlot slotOf(ResultSet rows, int n) throws SQLException {
    String type = rows.getString("profile_photo_type");
    return new PhotoSlot(
        new ProfessionalProfileId(rows.getObject("id", UUID.class)),
        rows.getLong("version"),
        rows.getString("profile_photo_key"),
        type == null ? null : FileType.valueOf(type));
  }
}
