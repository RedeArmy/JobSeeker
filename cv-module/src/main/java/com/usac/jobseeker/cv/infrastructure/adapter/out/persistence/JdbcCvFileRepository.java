package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles.CvFileReferencePort;
import com.usac.jobseeker.cv.application.usecase.uploadcv.CvFileStorePort;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CvFile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `candidate_cv_files` (`tdd/cv-module.md` §10.7). Las versiones borradas al
 * anonimizar no salen de ninguna lectura. «Una sola vigente por perfil» la garantiza el índice
 * único parcial; el bloqueo del perfil ordena las altas simultáneas para que nunca choquen con él.
 */
public class JdbcCvFileRepository implements CvFileStorePort, CvFileReferencePort {

  private static final String PROFILE = "profile";
  private static final String COLUMNS =
      "id, candidate_profile_id, object_key, display_name, file_type, size_bytes, sha256,"
          + " is_current, uploaded_at";
  private static final String LIVE = " from candidate_cv_files where deleted_at is null";
  private static final String SELECT_LIVE = "select " + COLUMNS + LIVE;

  private final JdbcClient jdbc;

  public JdbcCvFileRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CvFile> current(CandidateProfileId profile) {
    return jdbc.sql(SELECT_LIVE + " and candidate_profile_id = :profile and is_current")
        .param(PROFILE, profile.value())
        .query(JdbcCvFileRepository::fileOf)
        .optional();
  }

  @Override
  public List<CvFile> versions(CandidateProfileId profile) {
    // Más reciente primero, con el identificador como desempate para un orden estable.
    return jdbc.sql(
            SELECT_LIVE + " and candidate_profile_id = :profile order by uploaded_at desc, id")
        .param(PROFILE, profile.value())
        .query(JdbcCvFileRepository::fileOf)
        .list();
  }

  @Override
  public Optional<CvFile> find(CandidateProfileId profile, UUID fileId) {
    return jdbc.sql(SELECT_LIVE + " and candidate_profile_id = :profile and id = :id")
        .param(PROFILE, profile.value())
        .param("id", fileId)
        .query(JdbcCvFileRepository::fileOf)
        .optional();
  }

  @Override
  public void lockProfile(CandidateProfileId profile) {
    jdbc.sql("select id from candidate_profiles where id = :profile for update")
        .param(PROFILE, profile.value())
        .query(UUID.class)
        .optional();
  }

  @Override
  public int count(CandidateProfileId profile) {
    return jdbc.sql("select count(*)" + LIVE + " and candidate_profile_id = :profile")
        .param(PROFILE, profile.value())
        .query(Integer.class)
        .single();
  }

  @Override
  public void insertCurrent(CvFile file) {
    jdbc.sql(
            "update candidate_cv_files set is_current = false"
                + " where candidate_profile_id = :profile and is_current")
        .param(PROFILE, file.profileId().value())
        .update();
    jdbc.sql(
            "insert into candidate_cv_files (id, candidate_profile_id, object_key, display_name,"
                + " file_type, size_bytes, sha256, is_current, uploaded_at)"
                + " values (:id, :profile, :key, :name, :type, :size, :sha, true, :at)")
        .param("id", file.id())
        .param(PROFILE, file.profileId().value())
        .param("key", file.objectKey())
        .param("name", file.displayName())
        .param("type", file.type().name())
        .param("size", Math.toIntExact(file.sizeBytes()))
        .param("sha", file.sha256())
        .param("at", OffsetDateTime.ofInstant(file.uploadedAt(), ZoneOffset.UTC))
        .update();
  }

  // Una clave borrada al anonimizar o al reemplazar la foto ya es nula, así que no figura: su
  // objeto sí es huérfano. Cada propósito mira solo su columna.
  @Override
  public Set<String> referenced(FilePurpose purpose, Collection<String> keys) {
    if (keys.isEmpty()) {
      return Set.of();
    }
    String sql =
        switch (purpose) {
          case CV -> "select object_key from candidate_cv_files where object_key = any(:keys)";
          case PHOTO ->
              "select profile_photo_key from professional_profiles"
                  + " where profile_photo_key = any(:keys)";
          case ANSWER -> throw new IllegalArgumentException("Las respuestas no son de cv");
        };
    return new HashSet<>(
        jdbc.sql(sql).param("keys", keys.toArray(String[]::new)).query(String.class).list());
  }

  private static CvFile fileOf(ResultSet rows, int n) throws SQLException {
    return new CvFile(
        rows.getObject("id", UUID.class),
        new CandidateProfileId(rows.getObject("candidate_profile_id", UUID.class)),
        rows.getString("object_key"),
        rows.getString("display_name"),
        FileType.valueOf(rows.getString("file_type")),
        rows.getLong("size_bytes"),
        rows.getString("sha256"),
        rows.getBoolean("is_current"),
        rows.getObject("uploaded_at", OffsetDateTime.class).toInstant());
  }
}
