package com.usac.jobseeker.matching.domain.model;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Lo que `matching` sabe de un candidato, en tipos **propios** (capa anticorrupción: ningún tipo de
 * `cv` entra al dominio, ARCH-04). Es una proyección de lectura: el adaptador de `cv` la construye
 * solo con lo que el snapshot de `cv` devuelve, que ya no trae nombre, foto, contacto, enlaces ni
 * el nombre de empresas, instituciones o emisores. La forma misma de este tipo no tiene dónde
 * guardarlos.
 *
 * <p>Los valores de catálogo (modalidad, nivel educativo, nivel de idioma, periodo) se conservan
 * como el nombre que `cv` les da; las dimensiones del puntaje los interpretarán cuando existan.
 */
public record CandidateSignals(
    CandidateProfileId profileId,
    AccountId accountId,
    boolean discoverable,
    String headline,
    String countryOfResidence,
    String desiredModality,
    String preferredWorkSchedule,
    boolean relocationWillingness,
    Salary salary,
    List<Experience> experiences,
    List<Education> education,
    List<String> certifications,
    List<Project> projects,
    List<Skill> skills,
    List<Language> languages,
    List<String> workAuthorizations) {

  public CandidateSignals {
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(accountId, "accountId");
    experiences = List.copyOf(experiences);
    education = List.copyOf(education);
    certifications = List.copyOf(certifications);
    projects = List.copyOf(projects);
    skills = List.copyOf(skills);
    languages = List.copyOf(languages);
    workAuthorizations = List.copyOf(workAuthorizations);
  }

  public record Salary(BigDecimal min, BigDecimal max, String currency, String period) {}

  public record Experience(
      String title, LocalDate startDate, LocalDate endDate, String description) {}

  public record Education(String degree, String level, LocalDate completionDate) {}

  public record Project(String name, String description, String type, String role) {}

  public record Skill(String name, Integer level) {}

  public record Language(String language, String proficiency) {}
}
