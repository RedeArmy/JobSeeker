package com.usac.jobseeker.matching.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.DataStatus;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import com.usac.jobseeker.matching.domain.model.Weights;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * El agregador: la fórmula con `α` visible, la renormalización al excluir y las propiedades que el
 * ADR-0017 exige (límites, monotonía, idempotencia y el control C3).
 */
class CompatibilityAggregatorTest {

  private static final LocalDate TODAY = Signals.TODAY;
  private static final Weights WEIGHTS = Weights.defaults();
  private static final Calibration UNCALIBRATED = Calibration.uncalibrated("modelo");

  private static CompatibilityScore score(
      Signals.Candidate candidate, Signals.Job job, double cosine) {
    return CompatibilityAggregator.score(
        candidate.build(), job.build(), cosine, WEIGHTS, UNCALIBRATED, TODAY);
  }

  private static DimensionScore row(CompatibilityScore score, Dimension dimension) {
    return score.breakdown().dimensions().stream()
        .filter(d -> d.dimension() == dimension)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theBreakdownAlwaysHasOneRowPerDimensionAndAlphaIsVisible() {
    CompatibilityScore score =
        score(Signals.candidate().skill("Java", 3), Signals.job().skill("Java", true, null), 0.5);

    assertThat(score.breakdown().dimensions())
        .extracting(DimensionScore::dimension)
        .containsExactly(Dimension.values());
    assertThat(score.breakdown().alpha()).isEqualTo(0.30);
    assertThat(score.breakdown().dimensions())
        .allSatisfy(d -> assertThat(d.explanation()).isNotBlank());
  }

  @Test
  void theOverallIsTheDocumentedCombinationOfStructuredAndSemantic() {
    var candidate = Signals.candidate().skill("Java", 3);
    var job = Signals.job().skill("Java", true, null);

    CompatibilityScore score = score(candidate, job, 0.6);

    // Estructurado 1,0 (solo habilidades, que cubre); semántica sin calibrar = coseno acotado 0,6.
    assertThat(score.overall()).isCloseTo(100 * (0.7 * 1.0 + 0.3 * 0.6), within(1e-9));
    assertThat(score.breakdown().structured()).isEqualTo(1.0);
    assertThat(score.breakdown().semantic()).isEqualTo(0.6);
    assertThat(score.calibrated()).isFalse();
  }

  // Lo que la vacante no define no cuenta, y los pesos de las demás suman 1.
  @Test
  void weightsAreRenormalizedAmongTheAvailableDimensions() {
    var candidate =
        Signals.candidate().skill("Java", 3).education("BACHELOR", LocalDate.of(2015, 1, 1));
    var job = Signals.job().skill("Java", true, null).education("BACHELOR");

    CompatibilityScore score = score(candidate, job, 0.5);

    assertThat(row(score, Dimension.SKILLS).weight()).isCloseTo(0.35 / 0.50, within(1e-9));
    assertThat(row(score, Dimension.EDUCATION).weight()).isCloseTo(0.15 / 0.50, within(1e-9));
    assertThat(score.breakdown().dimensions().stream().mapToDouble(DimensionScore::weight).sum())
        .isCloseTo(1.0, within(1e-9));
    assertThat(row(score, Dimension.SALARY).weight()).isZero();
    assertThat(row(score, Dimension.SALARY).status()).isEqualTo(DataStatus.NOT_APPLICABLE);
  }

  @Test
  void whatTheCandidateDidNotDeclareLowersCompletenessButWhatTheJobDoesNotAskDoesNot() {
    var job = Signals.job().skill("Java", true, null).years(3);

    CompatibilityScore partial = score(Signals.candidate().skill("Java", 3), job, 0.5);
    CompatibilityScore full =
        score(
            Signals.candidate().skill("Java", 3).experience(LocalDate.of(2015, 1, 1), TODAY),
            job,
            0.5);
    CompatibilityScore jobAsksLess =
        score(Signals.candidate().skill("Java", 3), Signals.job().skill("Java", true, null), 0.5);

    // Pide habilidades (35) y experiencia (25): declaró habilidades.
    assertThat(partial.breakdown().completeness()).isCloseTo(0.35 / 0.60, within(1e-9));
    assertThat(full.breakdown().completeness()).isEqualTo(1.0);
    assertThat(jobAsksLess.breakdown().completeness()).isEqualTo(1.0);
  }

  @Test
  void withNothingStructuredToCompareTheScoreIsOnlySemanticAndSaysSo() {
    CompatibilityScore score = score(Signals.candidate(), Signals.job(), 0.8);

    assertThat(score.breakdown().structuredPresent()).isFalse();
    assertThat(score.breakdown().alpha()).isEqualTo(1.0);
    assertThat(score.overall()).isCloseTo(80.0, within(1e-9));
    assertThat(score.breakdown().completeness()).isEqualTo(1.0);
  }

  @Test
  void aJobThatAsksForThingsTheCandidateDeclaredNoneOfHasZeroCompleteness() {
    CompatibilityScore score =
        score(Signals.candidate(), Signals.job().skill("Java", true, null).years(2), 0.4);

    assertThat(score.breakdown().structuredPresent()).isFalse();
    assertThat(score.breakdown().completeness()).isZero();
  }

  @Test
  void aCalibratedSemanticReplacesTheRawCosine() {
    var calibration = new Calibration("modelo", Calibration.Kind.PLATT, 10, -5);

    CompatibilityScore score =
        CompatibilityAggregator.score(
            Signals.candidate().build(), Signals.job().build(), 0.5, WEIGHTS, calibration, TODAY);

    assertThat(score.calibrated()).isTrue();
    assertThat(score.breakdown().semantic()).isCloseTo(0.5, within(1e-9));
    assertThat(score.version().calibrationHash()).isEqualTo(calibration.hash());
  }

  @Test
  void theVersionChangesWithTheWeightsTheModelOrTheCalibration() {
    var candidate = Signals.candidate().build();
    var job = Signals.job().build();
    Map<Dimension, Double> heavier = new EnumMap<>(Dimension.class);
    for (Dimension d : Dimension.values()) {
      heavier.put(d, WEIGHTS.weight(d));
    }
    heavier.put(Dimension.SKILLS, 0.5);

    String base =
        CompatibilityAggregator.score(candidate, job, 0.5, WEIGHTS, UNCALIBRATED, TODAY)
            .version()
            .value();
    String otherWeights =
        CompatibilityAggregator.score(
                candidate, job, 0.5, new Weights(heavier, 0.3, 0.5, 2.0), UNCALIBRATED, TODAY)
            .version()
            .value();
    String otherModel =
        CompatibilityAggregator.score(
                candidate, job, 0.5, WEIGHTS, Calibration.uncalibrated("otro"), TODAY)
            .version()
            .value();

    assertThat(base).startsWith(CompatibilityAggregator.CODE_VERSION + "|modelo|");
    assertThat(otherWeights).isNotEqualTo(base);
    assertThat(otherModel).isNotEqualTo(base);
  }

  // ---- propiedades (ADR-0017, §9 del TDD) ----

  /** Un candidato y una vacante aleatorios, reproducibles por la semilla. */
  private static Signals.Candidate randomCandidate(Random random) {
    var c = Signals.candidate();
    String[] skills = {"Java", "SQL", "Go", "Docker", "Spring"};
    for (String skill : skills) {
      if (random.nextBoolean()) {
        c.skill(skill, random.nextBoolean() ? 1 + random.nextInt(5) : null);
      }
    }
    if (random.nextBoolean()) {
      c.experience(
          LocalDate.of(2010 + random.nextInt(14), 1, 1),
          random.nextBoolean() ? null : LocalDate.of(2025, 1, 1));
    }
    if (random.nextBoolean()) {
      c.education("BACHELOR", LocalDate.of(2015, 1, 1));
    }
    if (random.nextBoolean()) {
      c.language("inglés", "B" + (1 + random.nextInt(2)));
    }
    if (random.nextBoolean()) {
      c.modality("REMOTE").country("GT");
    }
    if (random.nextBoolean()) {
      c.salary("5000", "9000", "GTQ", "MONTH");
    }
    return c;
  }

  private static Signals.Job randomJob(Random random) {
    var j =
        Signals.job()
            .skill("Java", random.nextBoolean(), random.nextBoolean() ? 3 : null)
            .skill("SQL", false, null);
    if (random.nextBoolean()) {
      j.years(1 + random.nextInt(8));
    }
    if (random.nextBoolean()) {
      j.education("BACHELOR");
    }
    if (random.nextBoolean()) {
      j.language("inglés", true, "B2");
    }
    if (random.nextBoolean()) {
      j.modality("REMOTE").country("GT");
    }
    if (random.nextBoolean()) {
      j.salary("6000", "10000", "GTQ", "MONTH");
    }
    return j;
  }

  @Test
  void theOverallAndEveryFractionStayWithinTheirBoundsForAnyInput() {
    Random random = new Random(1);
    for (int i = 0; i < 500; i++) {
      CompatibilityScore s =
          score(randomCandidate(random), randomJob(random), random.nextDouble() * 2 - 0.5);

      assertThat(s.overall()).isBetween(0.0, 100.0);
      assertThat(s.breakdown().completeness()).isBetween(0.0, 1.0);
      assertThat(s.breakdown().structured()).isBetween(0.0, 1.0);
      assertThat(s.breakdown().semantic()).isBetween(0.0, 1.0);
      assertThat(s.breakdown().dimensions().stream().mapToDouble(DimensionScore::weight).sum())
          .satisfiesAnyOf(
              sum -> assertThat(sum).isCloseTo(1.0, within(1e-9)), sum -> assertThat(sum).isZero());
    }
  }

  @Test
  void theSameInputAlwaysGivesTheSameScore() {
    Random random = new Random(2);
    for (int i = 0; i < 100; i++) {
      long seed = random.nextLong();
      CompatibilityScore first =
          score(randomCandidate(new Random(seed)), randomJob(new Random(seed + 1)), 0.55);
      CompatibilityScore second =
          score(randomCandidate(new Random(seed)), randomJob(new Random(seed + 1)), 0.55);

      assertThat(first.overall()).isEqualTo(second.overall());
      assertThat(first.version()).isEqualTo(second.version());
    }
  }

  // Añadir una habilidad que cubre un requisito nunca baja el puntaje.
  @Test
  void addingASkillThatCoversARequirementNeverLowersTheScore() {
    Random random = new Random(3);
    for (int i = 0; i < 300; i++) {
      long seed = random.nextLong();
      var job = randomJob(new Random(seed));
      var without = randomCandidate(new Random(seed + 1));
      var with = randomCandidate(new Random(seed + 1));
      // Una habilidad pedida que el candidato aún no tenía, sin nivel exigible (no baja ni sube por
      // nivel).
      without.skills.removeIf(s -> s.name().equals("SQL"));
      with.skills.removeIf(s -> s.name().equals("SQL"));
      with.skill("SQL", 4);
      if (without.skills.isEmpty()) {
        // Sin ninguna habilidad la dimensión estaba excluida; con una, entra: puede variar la
        // mezcla.
        continue;
      }

      assertThat(score(with, job, 0.5).overall())
          .isGreaterThanOrEqualTo(score(without, job, 0.5).overall() - 1e-9);
    }
  }

  // Control C3 (ADR-0017): declarar un dato que coincide nunca baja el puntaje frente a omitirlo.
  @Test
  void declaringAMatchingDatumNeverLowersTheScoreComparedWithOmittingIt() {
    Random random = new Random(4);
    for (int i = 0; i < 300; i++) {
      long seed = random.nextLong();
      var job =
          Signals.job()
              .skill("Java", true, null)
              .years(2)
              .education("BACHELOR")
              .language("inglés", true, "B2")
              .modality("REMOTE")
              .salary("6000", "10000", "GTQ", "MONTH");
      var omitted = randomCandidate(new Random(seed));
      var declared = randomCandidate(new Random(seed));
      clear(omitted);
      clear(declared);
      omitted.skill("Java", 3);
      declared.skill("Java", 3);
      switch (random.nextInt(5)) {
        case 0 -> declared.experience(LocalDate.of(2010, 1, 1), TODAY);
        case 1 -> declared.education("MASTERS", LocalDate.of(2015, 1, 1));
        case 2 -> declared.language("inglés", "C1");
        case 3 -> declared.modality("REMOTE");
        default -> declared.salary("7000", "8000", "GTQ", "MONTH");
      }

      double cosine = random.nextDouble();
      assertThat(score(declared, job, cosine).overall())
          .isGreaterThanOrEqualTo(score(omitted, job, cosine).overall() - 1e-9);
    }
  }

  private static void clear(Signals.Candidate c) {
    c.skills.clear();
    c.experiences.clear();
    c.education.clear();
    c.languages.clear();
    c.modality = null;
    c.salary = null;
  }

  @Test
  void aNonNumericCosineIsRefused() {
    var candidate = Signals.candidate();
    var job = Signals.job();

    assertThatThrownBy(() -> score(candidate, job, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
