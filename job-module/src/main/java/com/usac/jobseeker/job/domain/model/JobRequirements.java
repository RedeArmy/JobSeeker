package com.usac.jobseeker.job.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.rules.FormatRules;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * El conjunto de requisitos de una vacante, validado como un todo: sin repetidos, con un tope y con
 * a lo sumo uno de formación y uno de experiencia (dos mínimos de lo mismo serían contradictorios).
 * El orden de la lista es el orden en que el reclutador los escribió.
 */
public final class JobRequirements {

  public static final int MAX = 30;

  private static final String TYPE = ".type";

  private JobRequirements() {}

  /** Valida cada requisito y el conjunto; falla una sola vez con todas las violaciones. */
  public static List<JobRequirement> of(List<JobRequirementDraft> drafts) {
    List<FieldViolation> violations = new ArrayList<>();
    List<JobRequirementDraft> input = drafts == null ? List.of() : drafts;
    if (input.size() > MAX) {
      violations.add(new FieldViolation("requirements", FormatRules.TOO_MANY));
      throw new ValidationFailedException(violations);
    }
    List<JobRequirement> clean = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < input.size(); i++) {
      String field = "requirements[" + i + "]";
      JobRequirement built = build(field, input.get(i), violations);
      if (built != null) {
        if (!seen.add(identity(built))) {
          violations.add(new FieldViolation(field, FormatRules.DUPLICATE));
        }
        clean.add(built);
      }
    }
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return List.copyOf(clean);
  }

  /** Si el conjunto alcanza para publicar (BR-JOB-02): al menos una habilidad o formación. */
  public static boolean satisfiesPublication(List<JobRequirement> requirements) {
    return requirements.stream().anyMatch(JobRequirement::isSkillOrEducation);
  }

  // Lo que hace a dos requisitos «el mismo»: la habilidad por su nombre, el idioma por su código y
  // la formación y la experiencia por su tipo (solo puede haber una de cada una).
  private static String identity(JobRequirement requirement) {
    return switch (requirement.type()) {
      case SKILL -> "S:" + FormatRules.sameKey(requirement.skillName());
      case LANGUAGE -> "L:" + requirement.language();
      case EDUCATION -> "E";
      case EXPERIENCE_YEARS -> "X";
    };
  }

  private static JobRequirement build(
      String field, JobRequirementDraft draft, List<FieldViolation> violations) {
    if (draft == null || draft.type() == null) {
      violations.add(new FieldViolation(field + TYPE, FormatRules.REQUIRED));
      return null;
    }
    int before = violations.size();
    boolean mandatory = Boolean.TRUE.equals(draft.mandatory());
    JobRequirement built =
        switch (draft.type()) {
          case SKILL -> skill(field, draft, mandatory, violations);
          case EDUCATION -> education(field, draft, mandatory, violations);
          case EXPERIENCE_YEARS -> years(field, draft, mandatory, violations);
          case LANGUAGE -> language(field, draft, mandatory, violations);
        };
    return violations.size() == before ? built : null;
  }

  private static JobRequirement skill(
      String field, JobRequirementDraft d, boolean mandatory, List<FieldViolation> violations) {
    String name =
        FormatRules.singleLine(field + ".skillName", d.skillName(), 100, true, violations);
    Integer level = d.minLevel();
    boolean levelOk =
        level == null || (level >= JobRequirement.LEVEL_MIN && level <= JobRequirement.LEVEL_MAX);
    if (!levelOk) {
      violations.add(new FieldViolation(field + ".minLevel", FormatRules.OUT_OF_RANGE));
    }
    boolean clean =
        !stray(
            field,
            violations,
            d.educationLevel() != null
                || d.minYears() != null
                || d.language() != null
                || d.minProficiency() != null);
    return name != null && levelOk && clean
        ? new JobRequirement(RequirementType.SKILL, mandatory, name, level, null, null, null, null)
        : null;
  }

  private static JobRequirement education(
      String field, JobRequirementDraft d, boolean mandatory, List<FieldViolation> violations) {
    if (d.educationLevel() == null) {
      violations.add(new FieldViolation(field + ".educationLevel", FormatRules.REQUIRED));
    }
    boolean clean =
        !stray(
            field,
            violations,
            d.skillName() != null
                || d.minLevel() != null
                || d.minYears() != null
                || d.language() != null
                || d.minProficiency() != null);
    return d.educationLevel() != null && clean
        ? new JobRequirement(
            RequirementType.EDUCATION, mandatory, null, null, d.educationLevel(), null, null, null)
        : null;
  }

  private static JobRequirement years(
      String field, JobRequirementDraft d, boolean mandatory, List<FieldViolation> violations) {
    Integer years = d.minYears();
    boolean yearsOk = years != null && years >= 1 && years <= JobRequirement.YEARS_MAX;
    if (years == null) {
      violations.add(new FieldViolation(field + ".minYears", FormatRules.REQUIRED));
    } else if (!yearsOk) {
      violations.add(new FieldViolation(field + ".minYears", FormatRules.OUT_OF_RANGE));
    }
    boolean clean =
        !stray(
            field,
            violations,
            d.skillName() != null
                || d.minLevel() != null
                || d.educationLevel() != null
                || d.language() != null
                || d.minProficiency() != null);
    return yearsOk && clean
        ? new JobRequirement(
            RequirementType.EXPERIENCE_YEARS, mandatory, null, null, null, years, null, null)
        : null;
  }

  private static JobRequirement language(
      String field, JobRequirementDraft d, boolean mandatory, List<FieldViolation> violations) {
    String code = FormatRules.language(field + ".language", d.language(), violations);
    if (d.minProficiency() == null) {
      violations.add(new FieldViolation(field + ".minProficiency", FormatRules.REQUIRED));
    }
    boolean clean =
        !stray(
            field,
            violations,
            d.skillName() != null
                || d.minLevel() != null
                || d.educationLevel() != null
                || d.minYears() != null);
    return code != null && d.minProficiency() != null && clean
        ? new JobRequirement(
            RequirementType.LANGUAGE, mandatory, null, null, null, null, code, d.minProficiency())
        : null;
  }

  // Un valor ajeno al tipo es un error del cliente: se rechaza en vez de ignorarlo en silencio.
  private static boolean stray(String field, List<FieldViolation> violations, boolean present) {
    if (present) {
      violations.add(new FieldViolation(field + TYPE, "FIELD_NOT_FOR_TYPE"));
    }
    return present;
  }
}
