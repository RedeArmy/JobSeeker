package com.usac.jobseeker.job.domain.model;

import java.util.Objects;

/**
 * Un requisito estructurado de la vacante (BR-JOB-02): lo que el score compara con el perfil. Cada
 * tipo usa sus propias columnas y el resto va nulo; el constructor lo exige, de modo que un
 * requisito mal formado no existe. La habilidad va por nombre en texto hasta que llegue la
 * taxonomía (ADR-0019).
 *
 * @param mandatory obligatorio o deseable
 */
public record JobRequirement(
    RequirementType type,
    boolean mandatory,
    String skillName,
    Integer minLevel,
    EducationLevel educationLevel,
    Integer minYears,
    String language,
    LanguageProficiency minProficiency) {

  public static final int LEVEL_MIN = 1;
  public static final int LEVEL_MAX = 4;
  public static final int YEARS_MAX = 60;

  public JobRequirement {
    Objects.requireNonNull(type, "type");
    boolean consistent =
        switch (type) {
          case SKILL ->
              skillName != null
                  && !skillName.isBlank()
                  && inRange(minLevel, LEVEL_MIN, LEVEL_MAX)
                  && educationLevel == null
                  && minYears == null
                  && language == null
                  && minProficiency == null;
          case EDUCATION ->
              educationLevel != null
                  && skillName == null
                  && minLevel == null
                  && minYears == null
                  && language == null
                  && minProficiency == null;
          case EXPERIENCE_YEARS ->
              minYears != null
                  && minYears >= 1
                  && minYears <= YEARS_MAX
                  && skillName == null
                  && minLevel == null
                  && educationLevel == null
                  && language == null
                  && minProficiency == null;
          case LANGUAGE ->
              language != null
                  && minProficiency != null
                  && skillName == null
                  && minLevel == null
                  && educationLevel == null
                  && minYears == null;
        };
    if (!consistent) {
      throw new IllegalArgumentException("el requisito no es coherente con su tipo");
    }
  }

  private static boolean inRange(Integer value, int min, int max) {
    return value == null || (value >= min && value <= max);
  }

  /** Si este requisito vale para la regla de publicar (BR-JOB-02: habilidad o formación). */
  public boolean isSkillOrEducation() {
    return type == RequirementType.SKILL || type == RequirementType.EDUCATION;
  }
}
