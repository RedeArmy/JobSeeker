package com.usac.jobseeker.matching.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.usac.jobseeker.matching.domain.model.DataStatus;
import com.usac.jobseeker.matching.domain.model.DimensionResult;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Las seis dimensiones: qué compara cada una y cuándo se excluye en vez de castigar (ADR-0017). */
class StructuredDimensionsTest {

  private static final double MANDATORY = 2.0;
  private static final LocalDate TODAY = Signals.TODAY;

  // ---- habilidades ----

  @Test
  void skillsAreNotApplicableWhenTheJobAsksForNone() {
    DimensionResult result =
        SkillDimensions.skills(
            Signals.candidate().skill("Java", 3).build(), Signals.job().build(), MANDATORY);

    assertThat(result.status()).isEqualTo(DataStatus.NOT_APPLICABLE);
  }

  @Test
  void skillsAreNotDeclaredWhenTheCandidateListsNone() {
    DimensionResult result =
        SkillDimensions.skills(
            Signals.candidate().build(),
            Signals.job().skill("Java", true, null).build(),
            MANDATORY);

    assertThat(result.status()).isEqualTo(DataStatus.NOT_DECLARED);
  }

  @Test
  void coveringEveryRequiredSkillScoresOneAndSaysSo() {
    var job = Signals.job().skill("Java", true, null).skill("SQL", false, null).build();
    var candidate = Signals.candidate().skill("java", 2).skill("sql", 4).build();

    DimensionResult result = SkillDimensions.skills(candidate, job, MANDATORY);

    assertThat(result.score()).isEqualTo(1.0);
    assertThat(result.explanation()).contains("Cubre las 2 habilidades");
  }

  // Un obligatorio pesa el doble: faltar uno obligatorio de dos baja más que faltar uno deseable.
  @Test
  void aMissingMandatorySkillCostsMoreThanAMissingOptionalOne() {
    var candidate = Signals.candidate().skill("Java", 3).build();
    var missingOptional = Signals.job().skill("Java", true, null).skill("Go", false, null).build();
    var missingMandatory = Signals.job().skill("Java", false, null).skill("Go", true, null).build();

    double optional = SkillDimensions.skills(candidate, missingOptional, MANDATORY).score();
    double mandatory = SkillDimensions.skills(candidate, missingMandatory, MANDATORY).score();

    assertThat(optional).isCloseTo(2.0 / 3.0, within(1e-9));
    assertThat(mandatory).isCloseTo(1.0 / 3.0, within(1e-9));
  }

  @Test
  void theMissingSkillsAreNamedInTheExplanation() {
    var job = Signals.job().skill("Java", true, null).skill("Kubernetes", true, null).build();

    DimensionResult result =
        SkillDimensions.skills(Signals.candidate().skill("Java", 3).build(), job, MANDATORY);

    assertThat(result.explanation()).contains("1 de 2").contains("faltan: Kubernetes");
  }

  @Test
  void namesAreComparedWithoutCaseAccentsOrPunctuation() {
    var job =
        Signals.job().skill("Node.js", true, null).skill("Administración", true, null).build();
    var candidate = Signals.candidate().skill("NODE JS", 3).skill("administracion", 3).build();

    assertThat(SkillDimensions.skills(candidate, job, MANDATORY).score()).isEqualTo(1.0);
  }

  @Test
  void aLevelBelowTheRequiredOneGivesProportionalCredit() {
    var job = Signals.job().skill("Java", true, 4).build();

    assertThat(
            SkillDimensions.skills(Signals.candidate().skill("Java", 2).build(), job, MANDATORY)
                .score())
        .isEqualTo(0.5);
    assertThat(
            SkillDimensions.skills(Signals.candidate().skill("Java", 5).build(), job, MANDATORY)
                .score())
        .isEqualTo(1.0);
  }

  // Sin nivel declarado no se puede verificar: la mitad, nunca más que declarar uno que cumple.
  @Test
  void aSkillWithoutADeclaredLevelGivesHalfCreditWhenALevelIsRequired() {
    var job = Signals.job().skill("Java", true, 3).build();

    assertThat(
            SkillDimensions.skills(Signals.candidate().skill("Java", null).build(), job, MANDATORY)
                .score())
        .isEqualTo(0.5);
  }

  @Test
  void aSkillWithoutALevelRequirementIsFullCreditEvenIfNoLevelWasDeclared() {
    var job = Signals.job().skill("Java", true, null).build();

    assertThat(
            SkillDimensions.skills(Signals.candidate().skill("Java", null).build(), job, MANDATORY)
                .score())
        .isEqualTo(1.0);
  }

  // ---- idiomas ----

  @Test
  void languagesAreNotApplicableOrNotDeclaredWhenThereIsNothingToCompare() {
    assertThat(
            SkillDimensions.languages(
                    Signals.candidate().language("inglés", "B2").build(),
                    Signals.job().build(),
                    MANDATORY)
                .status())
        .isEqualTo(DataStatus.NOT_APPLICABLE);
    assertThat(
            SkillDimensions.languages(
                    Signals.candidate().build(),
                    Signals.job().language("inglés", true, "B2").build(),
                    MANDATORY)
                .status())
        .isEqualTo(DataStatus.NOT_DECLARED);
  }

  @Test
  void aLanguageAtOrAboveTheRequiredLevelCovers() {
    var job = Signals.job().language("Inglés", true, "B2").build();

    assertThat(
            SkillDimensions.languages(
                    Signals.candidate().language("ingles", "C1").build(), job, MANDATORY)
                .score())
        .isEqualTo(1.0);
    assertThat(
            SkillDimensions.languages(
                    Signals.candidate().language("ingles", "NATIVE").build(), job, MANDATORY)
                .score())
        .isEqualTo(1.0);
  }

  @Test
  void aLowerLevelIsProportionalAndAnAbsentLanguageIsZero() {
    var job = Signals.job().language("inglés", true, "C2").build();

    assertThat(
            SkillDimensions.languages(
                    Signals.candidate().language("inglés", "B1").build(), job, MANDATORY)
                .score())
        .isCloseTo(3.0 / 6.0, within(1e-9));
    DimensionResult absent =
        SkillDimensions.languages(
            Signals.candidate().language("francés", "C1").build(), job, MANDATORY);
    assertThat(absent.score()).isZero();
    assertThat(absent.explanation()).contains("0 de 1").contains("inglés");
  }

  @Test
  void aLanguageDeclaredWithoutAKnownLevelIsHalfCreditNotZero() {
    var job = Signals.job().language("inglés", true, "B2").build();

    assertThat(
            SkillDimensions.languages(
                    Signals.candidate().language("inglés", null).build(), job, MANDATORY)
                .score())
        .isEqualTo(0.5);
  }

  @Test
  void aLanguageRequirementWithoutALevelOnlyNeedsTheLanguage() {
    var job = Signals.job().language("inglés", false, null).build();

    assertThat(
            SkillDimensions.languages(
                    Signals.candidate().language("inglés", "A1").build(), job, MANDATORY)
                .score())
        .isEqualTo(1.0);
  }

  // ---- experiencia ----

  @Test
  void overlappingJobsDoNotCountTwice() {
    var experiences =
        Signals.candidate()
            .experience(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1))
            .experience(LocalDate.of(2021, 1, 1), LocalDate.of(2023, 1, 1))
            .build()
            .experiences();

    assertThat(ProfileDimensions.yearsOf(experiences, TODAY)).isCloseTo(3.0, within(0.01));
  }

  @Test
  void anOngoingJobCountsUntilToday() {
    var experiences =
        Signals.candidate().experience(LocalDate.of(2025, 11, 25), null).build().experiences();

    assertThat(ProfileDimensions.yearsOf(experiences, TODAY)).isCloseTo(1.0, within(0.01));
  }

  @Test
  void anInvertedOrUndatedPeriodIsIgnored() {
    var experiences =
        Signals.candidate()
            .experience(LocalDate.of(2022, 1, 1), LocalDate.of(2021, 1, 1))
            .experience(null, LocalDate.of(2021, 1, 1))
            .build()
            .experiences();

    assertThat(ProfileDimensions.yearsOf(experiences, TODAY)).isZero();
  }

  @Test
  void experienceComparesTheTotalWithTheLargestRequirementAndIsCapped() {
    var job = Signals.job().years(4).years(2).build();
    var half = Signals.candidate().experience(LocalDate.of(2025, 11, 25), TODAY).build();
    var plenty = Signals.candidate().experience(LocalDate.of(2010, 1, 1), TODAY).build();

    assertThat(ProfileDimensions.experience(half, job, TODAY).score())
        .isCloseTo(0.25, within(0.01));
    assertThat(ProfileDimensions.experience(plenty, job, TODAY).score()).isEqualTo(1.0);
  }

  @Test
  void experienceIsExcludedWhenNotAskedOrNotDeclared() {
    assertThat(
            ProfileDimensions.experience(Signals.candidate().build(), Signals.job().build(), TODAY)
                .status())
        .isEqualTo(DataStatus.NOT_APPLICABLE);
    assertThat(
            ProfileDimensions.experience(
                    Signals.candidate().build(), Signals.job().years(3).build(), TODAY)
                .status())
        .isEqualTo(DataStatus.NOT_DECLARED);
  }

  @Test
  void aZeroYearRequirementIsAlwaysMet() {
    var candidate = Signals.candidate().experience(LocalDate.of(2025, 1, 1), TODAY).build();

    assertThat(
            ProfileDimensions.experience(candidate, Signals.job().years(0).build(), TODAY).score())
        .isEqualTo(1.0);
  }

  // ---- formación ----

  @Test
  void educationUsesTheHighestFinishedLevelAndIgnoresStudiesInProgress() {
    var job = Signals.job().education("MASTERS").build();
    var inProgress =
        Signals.candidate()
            .education("BACHELOR", LocalDate.of(2020, 1, 1))
            .education("MASTERS", null)
            .build();
    var future =
        Signals.candidate()
            .education("BACHELOR", LocalDate.of(2020, 1, 1))
            .education("MASTERS", LocalDate.of(2027, 1, 1))
            .build();

    assertThat(ProfileDimensions.education(inProgress, job, TODAY).score())
        .isCloseTo(3.0 / 5.0, within(1e-9));
    assertThat(ProfileDimensions.education(future, job, TODAY).score())
        .isCloseTo(3.0 / 5.0, within(1e-9));
  }

  @Test
  void aHigherLevelThanRequiredIsFullAndNeverMore() {
    var job = Signals.job().education("BACHELOR").build();
    var candidate = Signals.candidate().education("DOCTORATE", LocalDate.of(2015, 1, 1)).build();

    DimensionResult result = ProfileDimensions.education(candidate, job, TODAY);

    assertThat(result.score()).isEqualTo(1.0);
    assertThat(result.explanation()).contains("alcanza");
  }

  @Test
  void educationIsExcludedWhenNotAskedOrNothingIsFinished() {
    assertThat(
            ProfileDimensions.education(Signals.candidate().build(), Signals.job().build(), TODAY)
                .status())
        .isEqualTo(DataStatus.NOT_APPLICABLE);
    assertThat(
            ProfileDimensions.education(
                    Signals.candidate().education("BACHELOR", null).build(),
                    Signals.job().education("BACHELOR").build(),
                    TODAY)
                .status())
        .isEqualTo(DataStatus.NOT_DECLARED);
    assertThat(
            ProfileDimensions.education(
                    Signals.candidate().build(),
                    Signals.job().education("NO_EXISTE").build(),
                    TODAY)
                .status())
        .isEqualTo(DataStatus.NOT_APPLICABLE);
  }

  // ---- ubicación y modalidad ----

  @Test
  void aRemoteJobIgnoresTheCountry() {
    var job = Signals.job().modality("REMOTE").country("GT").build();
    var candidate = Signals.candidate().modality("REMOTE").country("MX").build();

    assertThat(FitDimensions.locationModality(candidate, job).score()).isEqualTo(1.0);
  }

  @Test
  void anOnsiteJobComparesModalityAndCountryEquallyAndRelocationHelps() {
    var job = Signals.job().modality("ONSITE").country("GT").build();

    assertThat(
            FitDimensions.locationModality(
                    Signals.candidate().modality("ONSITE").country("GT").build(), job)
                .score())
        .isEqualTo(1.0);
    assertThat(
            FitDimensions.locationModality(
                    Signals.candidate().modality("ONSITE").country("MX").relocation(true).build(),
                    job)
                .score())
        .isCloseTo(0.75, within(1e-9));
    assertThat(
            FitDimensions.locationModality(
                    Signals.candidate().modality("ONSITE").country("MX").build(), job)
                .score())
        .isCloseTo(0.5, within(1e-9));
    assertThat(
            FitDimensions.locationModality(
                    Signals.candidate().modality("REMOTE").country("MX").build(), job)
                .score())
        .isZero();
  }

  @Test
  void hybridIsPartiallyCompatibleWithTheOthers() {
    var job = Signals.job().modality("HYBRID").build();

    assertThat(
            FitDimensions.locationModality(Signals.candidate().modality("REMOTE").build(), job)
                .score())
        .isEqualTo(0.5);
  }

  @Test
  void anUndeclaredComponentIsOmittedNotAssumed() {
    var job = Signals.job().modality("ONSITE").country("GT").build();

    DimensionResult onlyCountry =
        FitDimensions.locationModality(Signals.candidate().country("GT").build(), job);

    assertThat(onlyCountry.score()).isEqualTo(1.0);
    assertThat(onlyCountry.explanation()).isEqualTo("Mismo país.");
    assertThat(FitDimensions.locationModality(Signals.candidate().build(), job).status())
        .isEqualTo(DataStatus.NOT_DECLARED);
    assertThat(
            FitDimensions.locationModality(
                    Signals.candidate().modality("REMOTE").build(), Signals.job().build())
                .status())
        .isEqualTo(DataStatus.NOT_APPLICABLE);
  }

  // ---- salario ----

  @Test
  void salaryIsExcludedWhenTheJobPublishesNoneOrTheCandidateDeclaresNone() {
    assertThat(
            FitDimensions.salary(
                    Signals.candidate().salary("1", "2", "GTQ", "MONTH").build(),
                    Signals.job().build())
                .status())
        .isEqualTo(DataStatus.NOT_APPLICABLE);
    assertThat(
            FitDimensions.salary(
                    Signals.candidate().build(),
                    Signals.job().salary("1", "2", "GTQ", "MONTH").build())
                .status())
        .isEqualTo(DataStatus.NOT_DECLARED);
    assertThat(
            FitDimensions.salary(
                    Signals.candidate().salary(null, null, "GTQ", "MONTH").build(),
                    Signals.job().salary("1", "2", "GTQ", "MONTH").build())
                .status())
        .isEqualTo(DataStatus.NOT_DECLARED);
    assertThat(
            FitDimensions.salary(
                    Signals.candidate().build(),
                    Signals.job().salary(null, null, "GTQ", "MONTH").build())
                .status())
        .isEqualTo(DataStatus.NOT_APPLICABLE);
  }

  // BR-MATCH-10: no se convierte entre monedas; se excluye y se dice.
  @Test
  void differentCurrenciesAreNotComparedAndTheBreakdownSaysSo() {
    DimensionResult result =
        FitDimensions.salary(
            Signals.candidate().salary("1000", "2000", "USD", "MONTH").build(),
            Signals.job().salary("8000", "12000", "GTQ", "MONTH").build());

    assertThat(result.status()).isEqualTo(DataStatus.NOT_COMPARABLE);
    assertThat(result.explanation()).contains("USD").contains("GTQ");
  }

  @Test
  void periodsAreBroughtToTheSameBasisBeforeComparing() {
    var job = Signals.job().salary("60000", "120000", "GTQ", "YEAR").build();

    assertThat(
            FitDimensions.salary(
                    Signals.candidate().salary("8000", "9000", "GTQ", "MONTH").build(), job)
                .score())
        .isEqualTo(1.0);
    // 11 000 al mes son 132 000 al año: supera el techo de 120 000.
    DimensionResult above =
        FitDimensions.salary(
            Signals.candidate().salary("11000", "12000", "GTQ", "MONTH").build(), job);
    assertThat(above.score()).isCloseTo(1 - (132000.0 - 120000.0) / 132000.0, within(1e-9));
    assertThat(above.explanation()).contains("supera");
  }

  // El rango abierto se compara con el extremo conocido: 5 000 al mes son 60 000 al año frente a un
  // único extremo de 48 000 ofrecido.
  @Test
  void aSingleSidedRangeIsComparedWithTheKnownEnd() {
    var candidate = Signals.candidate().salary(null, "5000", "GTQ", "MONTH").build();
    var job = Signals.job().salary("4000", null, "GTQ", "MONTH").build();

    assertThat(FitDimensions.salary(candidate, job).score()).isCloseTo(0.8, within(1e-9));
  }
}
