package com.usac.jobseeker.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobRequirementsTest {

  private static JobRequirementDraft skill(String name, Integer level, boolean mandatory) {
    return new JobRequirementDraft(
        RequirementType.SKILL, mandatory, name, level, null, null, null, null);
  }

  private static JobRequirementDraft education(EducationLevel level) {
    return new JobRequirementDraft(
        RequirementType.EDUCATION, false, null, null, level, null, null, null);
  }

  private static JobRequirementDraft years(Integer years) {
    return new JobRequirementDraft(
        RequirementType.EXPERIENCE_YEARS, true, null, null, null, years, null, null);
  }

  private static JobRequirementDraft language(String code, LanguageProficiency level) {
    return new JobRequirementDraft(
        RequirementType.LANGUAGE, false, null, null, null, null, code, level);
  }

  private static List<FieldViolation> violationsOf(JobRequirementDraft... drafts) {
    try {
      JobRequirements.of(List.of(drafts));
    } catch (ValidationFailedException failure) {
      return failure.violations();
    }
    return List.of();
  }

  private static FieldViolation v(String field, String code) {
    return new FieldViolation(field, code);
  }

  @Test
  void aMixedSetIsAcceptedKeepingTheOrderTheRecruiterWroteIt() {
    List<JobRequirement> set =
        JobRequirements.of(
            List.of(
                skill("  Java ", 3, true),
                education(EducationLevel.BACHELOR),
                years(5),
                language("EN", LanguageProficiency.B2)));

    assertThat(set)
        .extracting(JobRequirement::type)
        .containsExactly(
            RequirementType.SKILL,
            RequirementType.EDUCATION,
            RequirementType.EXPERIENCE_YEARS,
            RequirementType.LANGUAGE);
    assertThat(set.get(0).skillName()).isEqualTo("Java");
    assertThat(set.get(0).mandatory()).isTrue();
    assertThat(set.get(3).language()).isEqualTo("en");
  }

  @Test
  void anEmptyOrMissingSetIsAValidEmptySet() {
    assertThat(JobRequirements.of(List.of())).isEmpty();
    assertThat(JobRequirements.of(null)).isEmpty();
  }

  @Test
  void aMissingTypeIsRejected() {
    assertThat(
            violationsOf(new JobRequirementDraft(null, null, null, null, null, null, null, null)))
        .containsExactly(v("requirements[0].type", "REQUIRED"));
  }

  @Test
  void eachTypeRequiresItsOwnData() {
    assertThat(violationsOf(skill(" ", null, false)))
        .containsExactly(v("requirements[0].skillName", "REQUIRED"));
    assertThat(violationsOf(education(null)))
        .containsExactly(v("requirements[0].educationLevel", "REQUIRED"));
    assertThat(violationsOf(years(null)))
        .containsExactly(v("requirements[0].minYears", "REQUIRED"));
    assertThat(violationsOf(language(null, null)))
        .containsExactlyInAnyOrder(
            v("requirements[0].language", "REQUIRED"),
            v("requirements[0].minProficiency", "REQUIRED"));
  }

  @Test
  void rangesAreEnforced() {
    assertThat(violationsOf(skill("Java", 0, false)))
        .containsExactly(v("requirements[0].minLevel", "OUT_OF_RANGE"));
    assertThat(violationsOf(skill("Java", 5, false)))
        .containsExactly(v("requirements[0].minLevel", "OUT_OF_RANGE"));
    assertThat(violationsOf(years(0)))
        .containsExactly(v("requirements[0].minYears", "OUT_OF_RANGE"));
    assertThat(violationsOf(years(61)))
        .containsExactly(v("requirements[0].minYears", "OUT_OF_RANGE"));
    assertThat(violationsOf(language("zz", LanguageProficiency.A1)))
        .containsExactly(v("requirements[0].language", "INVALID_LANGUAGE"));
  }

  @Test
  void aValueThatBelongsToAnotherTypeIsRejectedInsteadOfIgnored() {
    assertThat(
            violationsOf(
                new JobRequirementDraft(
                    RequirementType.EDUCATION,
                    false,
                    "Java",
                    null,
                    EducationLevel.BACHELOR,
                    null,
                    null,
                    null)))
        .containsExactly(v("requirements[0].type", "FIELD_NOT_FOR_TYPE"));
    assertThat(
            violationsOf(
                new JobRequirementDraft(
                    RequirementType.SKILL, false, "Java", null, null, 3, null, null)))
        .containsExactly(v("requirements[0].type", "FIELD_NOT_FOR_TYPE"));
    assertThat(
            violationsOf(
                new JobRequirementDraft(
                    RequirementType.LANGUAGE,
                    false,
                    null,
                    null,
                    null,
                    2,
                    "en",
                    LanguageProficiency.B1)))
        .containsExactly(v("requirements[0].type", "FIELD_NOT_FOR_TYPE"));
    assertThat(
            violationsOf(
                new JobRequirementDraft(
                    RequirementType.EXPERIENCE_YEARS, false, null, null, null, 2, "en", null)))
        .containsExactly(v("requirements[0].type", "FIELD_NOT_FOR_TYPE"));
  }

  @Test
  void duplicatesAreRejected() {
    assertThat(violationsOf(skill("Java", null, false), skill(" java ", 2, true)))
        .containsExactly(v("requirements[1]", "DUPLICATE"));
    assertThat(violationsOf(education(EducationLevel.BACHELOR), education(EducationLevel.MASTERS)))
        .containsExactly(v("requirements[1]", "DUPLICATE"));
    assertThat(violationsOf(years(2), years(3))).containsExactly(v("requirements[1]", "DUPLICATE"));
    assertThat(
            violationsOf(
                language("en", LanguageProficiency.B1), language("EN", LanguageProficiency.C1)))
        .containsExactly(v("requirements[1]", "DUPLICATE"));
  }

  @Test
  void theSetHasATopAndEveryErrorIsReportedTogether() {
    List<JobRequirementDraft> tooMany = new ArrayList<>();
    for (int i = 0; i <= JobRequirements.MAX; i++) {
      tooMany.add(skill("Skill " + i, null, false));
    }

    assertThatThrownBy(() -> JobRequirements.of(tooMany))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            failure ->
                assertThat(failure.violations()).containsExactly(v("requirements", "TOO_MANY")));
    assertThat(violationsOf(skill("", null, false), years(0))).hasSize(2);
  }

  // BR-JOB-02: publicar exige habilidad o formación; solo años o idioma no alcanzan.
  @Test
  void onlyASkillOrAnEducationSatisfiesPublication() {
    assertThat(
            JobRequirements.satisfiesPublication(
                JobRequirements.of(List.of(skill("Java", null, false)))))
        .isTrue();
    assertThat(
            JobRequirements.satisfiesPublication(
                JobRequirements.of(List.of(education(EducationLevel.TECHNICAL)))))
        .isTrue();
    assertThat(
            JobRequirements.satisfiesPublication(
                JobRequirements.of(List.of(years(3), language("en", LanguageProficiency.B1)))))
        .isFalse();
    assertThat(JobRequirements.satisfiesPublication(List.of())).isFalse();
  }

  @Test
  void aRequirementThatIsNotCoherentWithItsTypeCannotBeBuilt() {
    assertThatThrownBy(
            () ->
                new JobRequirement(
                    RequirementType.SKILL, false, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new JobRequirement(
                    RequirementType.EDUCATION,
                    false,
                    "x",
                    null,
                    EducationLevel.BACHELOR,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Cada campo que no es del tipo se rechaza por separado: ninguno se ignora en silencio.
  @Test
  void everyForeignFieldIsRejectedForEveryType() {
    for (JobRequirementDraft draft :
        List.of(
            // SKILL con un campo de otro tipo
            new JobRequirementDraft(
                RequirementType.SKILL,
                false,
                "Java",
                null,
                EducationLevel.BACHELOR,
                null,
                null,
                null),
            new JobRequirementDraft(
                RequirementType.SKILL, false, "Java", null, null, 3, null, null),
            new JobRequirementDraft(
                RequirementType.SKILL, false, "Java", null, null, null, "en", null),
            new JobRequirementDraft(
                RequirementType.SKILL,
                false,
                "Java",
                null,
                null,
                null,
                null,
                LanguageProficiency.B1),
            // EDUCATION
            new JobRequirementDraft(
                RequirementType.EDUCATION,
                false,
                "Java",
                null,
                EducationLevel.BACHELOR,
                null,
                null,
                null),
            new JobRequirementDraft(
                RequirementType.EDUCATION,
                false,
                null,
                2,
                EducationLevel.BACHELOR,
                null,
                null,
                null),
            new JobRequirementDraft(
                RequirementType.EDUCATION,
                false,
                null,
                null,
                EducationLevel.BACHELOR,
                3,
                null,
                null),
            new JobRequirementDraft(
                RequirementType.EDUCATION,
                false,
                null,
                null,
                EducationLevel.BACHELOR,
                null,
                "en",
                null),
            new JobRequirementDraft(
                RequirementType.EDUCATION,
                false,
                null,
                null,
                EducationLevel.BACHELOR,
                null,
                null,
                LanguageProficiency.A1),
            // EXPERIENCE_YEARS
            new JobRequirementDraft(
                RequirementType.EXPERIENCE_YEARS, false, "Java", null, null, 3, null, null),
            new JobRequirementDraft(
                RequirementType.EXPERIENCE_YEARS, false, null, 2, null, 3, null, null),
            new JobRequirementDraft(
                RequirementType.EXPERIENCE_YEARS,
                false,
                null,
                null,
                EducationLevel.BACHELOR,
                3,
                null,
                null),
            new JobRequirementDraft(
                RequirementType.EXPERIENCE_YEARS, false, null, null, null, 3, "en", null),
            new JobRequirementDraft(
                RequirementType.EXPERIENCE_YEARS,
                false,
                null,
                null,
                null,
                3,
                null,
                LanguageProficiency.B1),
            // LANGUAGE
            new JobRequirementDraft(
                RequirementType.LANGUAGE,
                false,
                "Java",
                null,
                null,
                null,
                "en",
                LanguageProficiency.B2),
            new JobRequirementDraft(
                RequirementType.LANGUAGE, false, null, 2, null, null, "en", LanguageProficiency.B2),
            new JobRequirementDraft(
                RequirementType.LANGUAGE,
                false,
                null,
                null,
                EducationLevel.BACHELOR,
                null,
                "en",
                LanguageProficiency.B2),
            new JobRequirementDraft(
                RequirementType.LANGUAGE,
                false,
                null,
                null,
                null,
                3,
                "en",
                LanguageProficiency.B2))) {
      assertThat(violationsOf(draft))
          .as(draft.toString())
          .containsExactly(v("requirements[0].type", "FIELD_NOT_FOR_TYPE"));
    }
  }

  @Test
  void aNullElementIsATypeMissingNotACrash() {
    var withNull = new ArrayList<JobRequirementDraft>();
    withNull.add(null);

    assertThatThrownBy(() -> JobRequirements.of(withNull))
        .isInstanceOfSatisfying(
            ValidationFailedException.class,
            failure ->
                assertThat(failure.violations())
                    .containsExactly(v("requirements[0].type", "REQUIRED")));
  }

  // Un mismo error en dos elementos se reporta una vez por elemento, no una vez en total.
  @Test
  void eachBadElementReportsItsOwnPosition() {
    assertThat(violationsOf(years(0), years(70)))
        .containsExactly(
            v("requirements[0].minYears", "OUT_OF_RANGE"),
            v("requirements[1].minYears", "OUT_OF_RANGE"));
  }
}
