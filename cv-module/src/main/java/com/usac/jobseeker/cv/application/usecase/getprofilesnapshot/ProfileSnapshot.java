package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que `cv` entrega a otros módulos de un candidato: datos estructurados y de elegibilidad,
 * **sin** nombre, foto, biografía, correo, teléfono, enlaces ni el nombre de empresas,
 * instituciones o emisores. La minimización queda garantizada aquí, en el origen, y no confiada a
 * cada consumidor (`02-hld-system-design.md` §4.3, BR-DATA-01).
 *
 * <p>Los valores de catálogo (modalidad, jornada, nivel educativo, tipo de proyecto, nivel de
 * idioma y periodo) viajan como el nombre de su catálogo cerrado, no como el tipo de `cv`, para que
 * ningún tipo interno cruce el límite del módulo. El texto libre (descripciones) puede contener
 * datos que la persona escribió: redactarlos antes de generar un embedding es de quien lo consume
 * (ADR-0017).
 */
public record ProfileSnapshot(
    UUID candidateProfileId,
    UUID accountId,
    boolean discoverable,
    long professionalVersion,
    long candidateVersion,
    String headline,
    String countryOfResidence,
    String desiredModality,
    String preferredWorkSchedule,
    boolean relocationWillingness,
    Salary salary,
    List<Experience> experiences,
    List<Education> education,
    List<Certification> certifications,
    List<Project> projects,
    List<Skill> skills,
    List<Language> languages,
    List<String> workAuthorizations) {

  public ProfileSnapshot {
    Objects.requireNonNull(candidateProfileId, "candidateProfileId");
    Objects.requireNonNull(accountId, "accountId");
    experiences = List.copyOf(experiences);
    education = List.copyOf(education);
    certifications = List.copyOf(certifications);
    projects = List.copyOf(projects);
    skills = List.copyOf(skills);
    languages = List.copyOf(languages);
    workAuthorizations = List.copyOf(workAuthorizations);
  }

  /** Expectativa salarial: un rango con su moneda ISO 4217 y su periodo (`MONTH` o `YEAR`). */
  public record Salary(BigDecimal min, BigDecimal max, String currency, String period) {}

  /** Una experiencia sin el nombre de la empresa. */
  public record Experience(
      String title, LocalDate startDate, LocalDate endDate, String description) {}

  /** Una formación sin el nombre de la institución: el título y el nivel de catálogo. */
  public record Education(String degree, String level, LocalDate completionDate) {}

  /** Una certificación sin el emisor ni el enlace de la credencial. */
  public record Certification(String name, LocalDate issueDate, LocalDate expirationDate) {}

  /** Un proyecto sin su enlace. */
  public record Project(
      String name,
      String description,
      String type,
      String role,
      LocalDate startDate,
      LocalDate endDate) {}

  /** Una habilidad tal como la escribió la persona; el nivel (1 a 4) es opcional. */
  public record Skill(String name, Integer level) {}

  /** Un idioma ISO 639-1 y su nivel (`A1` a `C2` o `NATIVE`). */
  public record Language(String language, String proficiency) {}
}
