package com.usac.jobseeker.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Un requisito mal formado no existe: el constructor exige coherencia con su tipo. */
class JobRequirementTest {

  private static JobRequirement skill(String name, Integer level) {
    return new JobRequirement(RequirementType.SKILL, false, name, level, null, null, null, null);
  }

  private static void rejects(
      RequirementType type,
      String skillName,
      Integer minLevel,
      EducationLevel education,
      Integer minYears,
      String language,
      LanguageProficiency proficiency) {
    assertThatThrownBy(
            () ->
                new JobRequirement(
                    type, false, skillName, minLevel, education, minYears, language, proficiency))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSkillNeedsANameAndAnOptionalLevelFromOneToFour() {
    assertThat(skill("Java", null).minLevel()).isNull();
    assertThat(skill("Java", 1).minLevel()).isEqualTo(1);
    assertThat(skill("Java", 4).minLevel()).isEqualTo(4);
    rejects(RequirementType.SKILL, null, null, null, null, null, null);
    rejects(RequirementType.SKILL, " ", null, null, null, null, null);
    rejects(RequirementType.SKILL, "Java", 0, null, null, null, null);
    rejects(RequirementType.SKILL, "Java", 5, null, null, null, null);
  }

  @Test
  void aSkillCannotCarryTheFieldsOfAnotherType() {
    rejects(RequirementType.SKILL, "Java", null, EducationLevel.BACHELOR, null, null, null);
    rejects(RequirementType.SKILL, "Java", null, null, 3, null, null);
    rejects(RequirementType.SKILL, "Java", null, null, null, "en", null);
    rejects(RequirementType.SKILL, "Java", null, null, null, null, LanguageProficiency.B1);
  }

  @Test
  void anEducationNeedsALevelAndNothingElse() {
    var ok =
        new JobRequirement(
            RequirementType.EDUCATION, true, null, null, EducationLevel.MASTERS, null, null, null);

    assertThat(ok.educationLevel()).isEqualTo(EducationLevel.MASTERS);
    rejects(RequirementType.EDUCATION, null, null, null, null, null, null);
    rejects(RequirementType.EDUCATION, "Java", null, EducationLevel.BACHELOR, null, null, null);
    rejects(RequirementType.EDUCATION, null, 2, EducationLevel.BACHELOR, null, null, null);
    rejects(RequirementType.EDUCATION, null, null, EducationLevel.BACHELOR, 3, null, null);
    rejects(RequirementType.EDUCATION, null, null, EducationLevel.BACHELOR, null, "en", null);
    rejects(
        RequirementType.EDUCATION,
        null,
        null,
        EducationLevel.BACHELOR,
        null,
        null,
        LanguageProficiency.A1);
  }

  @Test
  void yearsOfExperienceAreBetweenOneAndSixty() {
    var one =
        new JobRequirement(
            RequirementType.EXPERIENCE_YEARS, false, null, null, null, 1, null, null);
    var sixty =
        new JobRequirement(
            RequirementType.EXPERIENCE_YEARS, false, null, null, null, 60, null, null);

    assertThat(one.minYears()).isEqualTo(1);
    assertThat(sixty.minYears()).isEqualTo(60);
    rejects(RequirementType.EXPERIENCE_YEARS, null, null, null, null, null, null);
    rejects(RequirementType.EXPERIENCE_YEARS, null, null, null, 0, null, null);
    rejects(RequirementType.EXPERIENCE_YEARS, null, null, null, 61, null, null);
    rejects(RequirementType.EXPERIENCE_YEARS, "Java", null, null, 3, null, null);
    rejects(RequirementType.EXPERIENCE_YEARS, null, 2, null, 3, null, null);
    rejects(RequirementType.EXPERIENCE_YEARS, null, null, EducationLevel.BACHELOR, 3, null, null);
    rejects(RequirementType.EXPERIENCE_YEARS, null, null, null, 3, "en", null);
    rejects(RequirementType.EXPERIENCE_YEARS, null, null, null, 3, null, LanguageProficiency.B1);
  }

  @Test
  void aLanguageNeedsACodeAndALevelAndNothingElse() {
    var ok =
        new JobRequirement(
            RequirementType.LANGUAGE, false, null, null, null, null, "en", LanguageProficiency.B2);

    assertThat(ok.language()).isEqualTo("en");
    rejects(RequirementType.LANGUAGE, null, null, null, null, null, LanguageProficiency.B2);
    rejects(RequirementType.LANGUAGE, null, null, null, null, "en", null);
    rejects(RequirementType.LANGUAGE, "Java", null, null, null, "en", LanguageProficiency.B2);
    rejects(RequirementType.LANGUAGE, null, 2, null, null, "en", LanguageProficiency.B2);
    rejects(
        RequirementType.LANGUAGE,
        null,
        null,
        EducationLevel.BACHELOR,
        null,
        "en",
        LanguageProficiency.B2);
    rejects(RequirementType.LANGUAGE, null, null, null, 3, "en", LanguageProficiency.B2);
  }

  @Test
  void aTypeIsAlwaysRequired() {
    assertThatThrownBy(() -> new JobRequirement(null, false, "Java", null, null, null, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  // BR-JOB-02: solo una habilidad o una formación alcanzan para publicar.
  @Test
  void onlySkillsAndEducationsCountForPublishing() {
    assertThat(skill("Java", null).isSkillOrEducation()).isTrue();
    assertThat(
            new JobRequirement(
                    RequirementType.EDUCATION,
                    false,
                    null,
                    null,
                    EducationLevel.BACHELOR,
                    null,
                    null,
                    null)
                .isSkillOrEducation())
        .isTrue();
    assertThat(
            new JobRequirement(
                    RequirementType.EXPERIENCE_YEARS, false, null, null, null, 3, null, null)
                .isSkillOrEducation())
        .isFalse();
    assertThat(
            new JobRequirement(
                    RequirementType.LANGUAGE,
                    false,
                    null,
                    null,
                    null,
                    null,
                    "en",
                    LanguageProficiency.B1)
                .isSkillOrEducation())
        .isFalse();
  }
}
