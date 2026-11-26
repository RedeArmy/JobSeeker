package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Constructores de señales para las pruebas: candidato y vacante vacíos que cada prueba completa.
 */
final class Signals {

  static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

  private Signals() {}

  /** Un candidato descubrible sin ningún dato declarado. */
  static Candidate candidate() {
    return new Candidate();
  }

  /** Una vacante publicada sin requisitos. */
  static Job job() {
    return new Job();
  }

  static final class Candidate {
    String headline;
    String country;
    String modality;
    boolean relocation;
    CandidateSignals.Salary salary;
    final List<CandidateSignals.Experience> experiences = new ArrayList<>();
    final List<CandidateSignals.Education> education = new ArrayList<>();
    final List<CandidateSignals.Skill> skills = new ArrayList<>();
    final List<CandidateSignals.Language> languages = new ArrayList<>();

    Candidate country(String value) {
      country = value;
      return this;
    }

    Candidate modality(String value) {
      modality = value;
      return this;
    }

    Candidate relocation(boolean value) {
      relocation = value;
      return this;
    }

    Candidate headline(String value) {
      headline = value;
      return this;
    }

    Candidate salary(String min, String max, String currency, String period) {
      salary =
          new CandidateSignals.Salary(
              min == null ? null : new BigDecimal(min),
              max == null ? null : new BigDecimal(max),
              currency,
              period);
      return this;
    }

    Candidate skill(String name, Integer level) {
      skills.add(new CandidateSignals.Skill(name, level));
      return this;
    }

    Candidate language(String name, String proficiency) {
      languages.add(new CandidateSignals.Language(name, proficiency));
      return this;
    }

    Candidate experience(LocalDate start, LocalDate end) {
      experiences.add(new CandidateSignals.Experience("Ingeniero", start, end, "texto"));
      return this;
    }

    Candidate education(String level, LocalDate completion) {
      education.add(new CandidateSignals.Education("Título", level, completion));
      return this;
    }

    CandidateSignals build() {
      return new CandidateSignals(
          new CandidateProfileId(UUID.randomUUID()),
          new AccountId(UUID.randomUUID()),
          true,
          headline,
          country,
          modality,
          null,
          relocation,
          salary,
          experiences,
          education,
          List.of(),
          List.of(),
          skills,
          languages,
          List.of());
    }
  }

  static final class Job {
    String modality;
    String country;
    JobSignals.Salary salary;
    final List<JobSignals.Requirement> requirements = new ArrayList<>();

    Job modality(String value) {
      modality = value;
      return this;
    }

    Job country(String value) {
      country = value;
      return this;
    }

    Job salary(String min, String max, String currency, String period) {
      salary =
          new JobSignals.Salary(
              min == null ? null : new BigDecimal(min),
              max == null ? null : new BigDecimal(max),
              currency,
              period);
      return this;
    }

    Job skill(String name, boolean mandatory, Integer minLevel) {
      requirements.add(
          new JobSignals.Requirement("SKILL", mandatory, name, minLevel, null, null, null, null));
      return this;
    }

    Job years(int minYears) {
      requirements.add(
          new JobSignals.Requirement(
              "EXPERIENCE_YEARS", true, null, null, null, minYears, null, null));
      return this;
    }

    Job education(String level) {
      requirements.add(
          new JobSignals.Requirement("EDUCATION", true, null, null, level, null, null, null));
      return this;
    }

    Job language(String name, boolean mandatory, String minProficiency) {
      requirements.add(
          new JobSignals.Requirement(
              "LANGUAGE", mandatory, null, null, null, null, name, minProficiency));
      return this;
    }

    JobSignals build() {
      return new JobSignals(
          new JobPostingId(UUID.randomUUID()),
          1,
          true,
          false,
          "PUBLISHED",
          "Backend",
          "Descripción",
          modality,
          country,
          salary,
          requirements);
    }
  }
}
