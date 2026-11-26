package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata.AnonymizationStorePort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Anonimización de las tablas de `cv` (`data-model.md` §4.2, §4.4, §4.10 y §4.12). La condición
 * `anonymized_at is null` de cada `UPDATE` es lo que la hace idempotente: la segunda vez no
 * encuentra fila y no borra nada. Las filas hijas se borran **después** de marcar el perfil y solo
 * si esta llamada lo marcó, en la misma transacción del caso de uso.
 *
 * <p>Se borran físicamente porque a diferencia de las postulaciones o los scores, nadie más tiene
 * un interés legítimo en conservar el detalle de la experiencia de quien pidió ser olvidado
 * (BR-DATA-05).
 */
public class JdbcAnonymizationRepository implements AnonymizationStorePort {

  private static final String PROFILE = "profile";

  private final JdbcClient jdbc;

  public JdbcAnonymizationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Anonymized> anonymizeProfessional(
      AccountId accountId, String placeholderName, Instant now) {
    Optional<Anonymized> marked =
        jdbc.sql(
                "update professional_profiles set full_name = :name, headline = null, bio = null,"
                    + " profile_photo_key = null, profile_photo_type = null, anonymized_at = :now,"
                    + " version = version + 1,"
                    + " updated_at = :now where account_id = :account and anonymized_at is null"
                    + " returning id, version")
            .param("name", placeholderName)
            .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("account", accountId.value())
            .query(
                (rows, n) ->
                    new Anonymized(rows.getObject("id", UUID.class), rows.getLong("version")))
            .optional();
    marked.ifPresent(this::deleteProfessionalChildren);
    return marked;
  }

  @Override
  public Optional<Anonymized> anonymizeCandidate(AccountId accountId, Instant now) {
    Optional<Anonymized> marked =
        jdbc.sql(
                "update candidate_profiles set contact_email = null, contact_phone = null,"
                    + " discoverable = false, availability_status = 'UNSET',"
                    + " salary_expectation_min = null, salary_expectation_max = null,"
                    + " salary_currency = null, salary_period = null,"
                    + " anonymized_at = :now, version = version + 1,"
                    + " updated_at = :now where account_id = :account and anonymized_at is null"
                    + " returning id, version")
            .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("account", accountId.value())
            .query(
                (rows, n) ->
                    new Anonymized(rows.getObject("id", UUID.class), rows.getLong("version")))
            .optional();
    marked.ifPresent(this::deleteCandidateChildren);
    return marked;
  }

  // Una sola sentencia: lee las claves vigentes y las borra a la vez, así ninguna se escapa entre
  // la
  // lectura y la escritura.
  @Override
  public List<String> discardCvFiles(UUID candidateProfileId, Instant now) {
    return jdbc.sql(
            "with old as (select id, object_key from candidate_cv_files"
                + " where candidate_profile_id = :profile and deleted_at is null for update)"
                + " update candidate_cv_files f set object_key = null, display_name = null,"
                + " is_current = false, deleted_at = :now from old where f.id = old.id"
                + " returning old.object_key")
        .param(PROFILE, candidateProfileId)
        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .query(String.class)
        .list();
  }

  @Override
  public Optional<String> discardProfilePhoto(AccountId accountId) {
    return jdbc.sql(
            "with old as (select id, profile_photo_key from professional_profiles"
                + " where account_id = :account and anonymized_at is null"
                + " and profile_photo_key is not null for update)"
                + " update professional_profiles p set profile_photo_key = null,"
                + " profile_photo_type = null from old where p.id = old.id"
                + " returning old.profile_photo_key")
        .param("account", accountId.value())
        .query(String.class)
        .optional();
  }

  // Cada sentencia es completa y fija: no se concatena ningún nombre de tabla.
  private void deleteProfessionalChildren(Anonymized profile) {
    jdbc.sql("delete from experiences where professional_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
    jdbc.sql("delete from education_entries where professional_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
    jdbc.sql("delete from certifications where professional_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
    jdbc.sql("delete from projects where professional_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
  }

  private void deleteCandidateChildren(Anonymized profile) {
    jdbc.sql("delete from candidate_skills where candidate_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
    jdbc.sql("delete from candidate_languages where candidate_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
    jdbc.sql("delete from candidate_work_authorizations where candidate_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
    // Es una relación entre personas: se borran las del candidato anonimizado. Las filas donde él
    // es
    // la cuenta *excluida* se conservan, porque no contienen datos suyos (data-model.md §4.10).
    jdbc.sql("delete from candidate_availability_exclusions where candidate_profile_id = :profile")
        .param(PROFILE, profile.profileId())
        .update();
  }
}
