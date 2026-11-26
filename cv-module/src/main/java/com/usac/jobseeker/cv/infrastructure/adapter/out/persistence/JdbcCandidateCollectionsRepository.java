package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.CandidateLanguagesStorePort;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.CandidateSkillsStorePort;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.WorkAuthorizationsStorePort;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lectura y reemplazo de los tres conjuntos del perfil de candidato (`data-model.md` §4.12). Los
 * métodos de escritura tienen nombres distintos para que una sola clase pueda implementar los tres
 * puertos. Reemplazar es borrar todo y escribir lo nuevo, siempre dentro de la transacción del caso
 * de uso: si algo falla, el conjunto anterior queda intacto.
 */
public class JdbcCandidateCollectionsRepository
    implements CandidateCollectionsReadPort,
        CandidateSkillsStorePort,
        CandidateLanguagesStorePort,
        WorkAuthorizationsStorePort {

  private static final String PROFILE = "profile";
  private static final String PROFILES = "profiles";

  private final JdbcClient jdbc;

  public JdbcCandidateCollectionsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public CandidateCollections findByProfileId(CandidateProfileId profileId) {
    return findAllByProfileIds(List.of(profileId)).get(profileId);
  }

  // Tres consultas para todos los perfiles pedidos, agrupadas por dueño (TD-039); la individual es
  // el
  // caso de uno. Un orden estable: el conjunto no tiene orden propio y una lectura no debe
  // cambiarlo
  // al azar.
  @Override
  public Map<CandidateProfileId, CandidateCollections> findAllByProfileIds(
      Collection<CandidateProfileId> profileIds) {
    UUID[] ids = profileIds.stream().map(CandidateProfileId::value).toArray(UUID[]::new);
    Map<UUID, List<CandidateSkill>> skills = new HashMap<>();
    jdbc.sql(
            "select candidate_profile_id, raw_name, proficiency from candidate_skills"
                + " where candidate_profile_id = any(:profiles) order by lower(btrim(raw_name))")
        .param(PROFILES, ids)
        .query(
            (rows, n) -> {
              // `wasNull` se consulta justo tras `getInt`: cualquier otra lectura lo reinicia.
              int level = rows.getInt("proficiency");
              Integer stored = rows.wasNull() ? null : level;
              return skills
                  .computeIfAbsent(owner(rows), k -> new ArrayList<>())
                  .add(new CandidateSkill(rows.getString("raw_name"), stored));
            })
        .list();
    Map<UUID, List<CandidateLanguage>> languages = new HashMap<>();
    jdbc.sql(
            "select candidate_profile_id, language, proficiency from candidate_languages"
                + " where candidate_profile_id = any(:profiles) order by language")
        .param(PROFILES, ids)
        .query(
            (rows, n) ->
                languages
                    .computeIfAbsent(owner(rows), k -> new ArrayList<>())
                    .add(
                        new CandidateLanguage(
                            rows.getString("language"),
                            LanguageProficiency.valueOf(rows.getString("proficiency")))))
        .list();
    Map<UUID, List<String>> authorizations = new HashMap<>();
    jdbc.sql(
            "select candidate_profile_id, country from candidate_work_authorizations"
                + " where candidate_profile_id = any(:profiles) order by country")
        .param(PROFILES, ids)
        .query(
            (rows, n) ->
                authorizations
                    .computeIfAbsent(owner(rows), k -> new ArrayList<>())
                    .add(rows.getString("country")))
        .list();
    Map<CandidateProfileId, CandidateCollections> all = new HashMap<>();
    for (CandidateProfileId profile : profileIds) {
      UUID id = profile.value();
      all.put(
          profile,
          new CandidateCollections(
              skills.getOrDefault(id, List.of()),
              languages.getOrDefault(id, List.of()),
              authorizations.getOrDefault(id, List.of())));
    }
    return all;
  }

  private static UUID owner(java.sql.ResultSet rows) throws java.sql.SQLException {
    return rows.getObject("candidate_profile_id", UUID.class);
  }

  // Una sola sentencia por conjunto, con `unnest` sobre arreglos paralelos: antes eran N
  // inserciones
  // (hasta 100 viajes a la base de datos en una petición) dentro de la transacción que mantiene el
  // bloqueo de la fila del perfil. Un conjunto vacío no inserta nada.
  @Override
  public void replaceSkills(CandidateProfileId profileId, List<CandidateSkill> items) {
    clear("candidate_skills", profileId);
    if (items.isEmpty()) {
      return;
    }
    jdbc.sql(
            "insert into candidate_skills (id, candidate_profile_id, raw_name, proficiency)"
                + " select t.id, :profile, t.name, t.level"
                + " from unnest(cast(:ids as uuid[]), cast(:names as text[]),"
                + " cast(:levels as integer[])) as t(id, name, level)")
        .param(PROFILE, profileId.value())
        .param("ids", newIds(items.size()))
        .param("names", items.stream().map(CandidateSkill::name).toArray(String[]::new))
        .param("levels", items.stream().map(CandidateSkill::level).toArray(Integer[]::new))
        .update();
  }

  @Override
  public void replaceLanguages(CandidateProfileId profileId, List<CandidateLanguage> items) {
    clear("candidate_languages", profileId);
    if (items.isEmpty()) {
      return;
    }
    jdbc.sql(
            "insert into candidate_languages (id, candidate_profile_id, language, proficiency)"
                + " select t.id, :profile, t.language, t.level"
                + " from unnest(cast(:ids as uuid[]), cast(:languages as text[]),"
                + " cast(:levels as text[])) as t(id, language, level)")
        .param(PROFILE, profileId.value())
        .param("ids", newIds(items.size()))
        .param("languages", items.stream().map(CandidateLanguage::language).toArray(String[]::new))
        .param("levels", items.stream().map(l -> l.proficiency().name()).toArray(String[]::new))
        .update();
  }

  @Override
  public void replaceWorkAuthorizations(CandidateProfileId profileId, List<String> items) {
    clear("candidate_work_authorizations", profileId);
    if (items.isEmpty()) {
      return;
    }
    jdbc.sql(
            "insert into candidate_work_authorizations (id, candidate_profile_id, country)"
                + " select t.id, :profile, t.country"
                + " from unnest(cast(:ids as uuid[]), cast(:countries as text[]))"
                + " as t(id, country)")
        .param(PROFILE, profileId.value())
        .param("ids", newIds(items.size()))
        .param("countries", items.toArray(String[]::new))
        .update();
  }

  private static UUID[] newIds(int count) {
    UUID[] ids = new UUID[count];
    for (int i = 0; i < count; i++) {
      ids[i] = UUID.randomUUID();
    }
    return ids;
  }

  private void clear(String table, CandidateProfileId profileId) {
    // Cada tabla tiene su sentencia completa y fija: no se concatena ningún nombre.
    String sql =
        switch (table) {
          case "candidate_skills" ->
              "delete from candidate_skills where candidate_profile_id = :profile";
          case "candidate_languages" ->
              "delete from candidate_languages where candidate_profile_id = :profile";
          case "candidate_work_authorizations" ->
              "delete from candidate_work_authorizations where candidate_profile_id = :profile";
          default -> throw new IllegalArgumentException("tabla desconocida");
        };
    jdbc.sql(sql).param(PROFILE, profileId.value()).update();
  }
}
