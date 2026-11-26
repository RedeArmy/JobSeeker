package com.usac.jobseeker.matching.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Los tipos del puntaje validan lo que el ADR-0017 exige y no aceptan configuraciones absurdas. */
class ScoringTypesTest {

  private static Map<Dimension, Double> all(double value) {
    Map<Dimension, Double> weights = new EnumMap<>(Dimension.class);
    for (Dimension d : Dimension.values()) {
      weights.put(d, value);
    }
    return weights;
  }

  @Test
  void theDefaultWeightsAreTheDocumentedOnesAndSumToOne() {
    Weights weights = Weights.defaults();

    assertThat(weights.weight(Dimension.SKILLS)).isEqualTo(0.35);
    assertThat(weights.weight(Dimension.LANGUAGES)).isEqualTo(0.05);
    assertThat(weights.structured().values().stream().mapToDouble(Double::doubleValue).sum())
        .isCloseTo(1.0, within(1e-9));
    assertThat(weights.alpha()).isEqualTo(0.30);
    assertThat(weights.minCompleteness()).isEqualTo(0.5);
  }

  @Test
  void incoherentWeightsAreRefused() {
    Map<Dimension, Double> missing = all(1);
    missing.remove(Dimension.SALARY);
    Map<Dimension, Double> negative = all(1);
    negative.put(Dimension.SKILLS, -1.0);
    Map<Dimension, Double> notANumber = all(1);
    notANumber.put(Dimension.SKILLS, Double.NaN);

    assertThatThrownBy(() -> new Weights(missing, 0.3, 0.5, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Weights(negative, 0.3, 0.5, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Weights(notANumber, 0.3, 0.5, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Weights(all(0), 0.3, 0.5, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Weights(all(1), 1.1, 0.5, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Weights(all(1), 0.3, -0.1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Weights(all(1), 0.3, 0.5, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theWeightsHashChangesWithAnyParameterAndIsStableOtherwise() {
    Weights base = Weights.defaults();

    assertThat(base.hash()).isEqualTo(Weights.defaults().hash()).hasSize(64);
    assertThat(new Weights(base.structured(), 0.4, 0.5, 2).hash()).isNotEqualTo(base.hash());
    assertThat(new Weights(base.structured(), 0.3, 0.6, 2).hash()).isNotEqualTo(base.hash());
    assertThat(new Weights(base.structured(), 0.3, 0.5, 3).hash()).isNotEqualTo(base.hash());
  }

  @Test
  void uncalibratedClampsTheCosineAndPlattIsAMonotoneSigmoid() {
    Calibration none = Calibration.uncalibrated("m");
    Calibration platt = new Calibration("m", Calibration.Kind.PLATT, 8, -4);

    assertThat(none.calibrated()).isFalse();
    assertThat(none.apply(-0.3)).isZero();
    assertThat(none.apply(1.4)).isEqualTo(1.0);
    assertThat(none.apply(0.42)).isEqualTo(0.42);
    assertThat(platt.calibrated()).isTrue();
    assertThat(platt.apply(0.5)).isCloseTo(0.5, within(1e-9));
    assertThat(platt.apply(0.9)).isGreaterThan(platt.apply(0.4));
    assertThat(platt.apply(1.0)).isBetween(0.0, 1.0);
  }

  @Test
  void aBadCalibrationIsRefused() {
    assertThatThrownBy(() -> new Calibration("m", Calibration.Kind.PLATT, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Calibration("m", Calibration.Kind.PLATT, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Calibration("m", Calibration.Kind.PLATT, 1, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Calibration.uncalibrated("m").apply(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theCalibrationHashDependsOnModelKindAndParameters() {
    Calibration platt = new Calibration("m", Calibration.Kind.PLATT, 8, -4);

    assertThat(platt.hash()).isNotEqualTo(Calibration.uncalibrated("m").hash());
    assertThat(platt.hash())
        .isNotEqualTo(new Calibration("m", Calibration.Kind.PLATT, 9, -4).hash());
    assertThat(platt.hash())
        .isNotEqualTo(new Calibration("otro", Calibration.Kind.PLATT, 8, -4).hash());
  }

  @Test
  void theDimensionResultAndTheScoreValidateTheirRanges() {
    assertThatThrownBy(() -> DimensionResult.available(1.2, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DimensionResult.available(Double.NaN, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(DimensionResult.notComparable("x").status()).isEqualTo(DataStatus.NOT_COMPARABLE);
    ScoreBreakdown breakdown = new ScoreBreakdown(List.of(), false, 0, 0, 1, 1);
    AlgorithmVersion version = new AlgorithmVersion("1", "m", "a".repeat(64), "b".repeat(64));
    assertThatThrownBy(() -> new CompatibilityScore(101, breakdown, version, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CompatibilityScore(-1, breakdown, version, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(version.value()).isEqualTo("1|m|aaaaaaaa|bbbbbbbb");
  }

  @Test
  void anUnknownAvailabilityIsUnset() {
    assertThat(Availability.of("OPEN_TO_WORK")).isEqualTo(Availability.OPEN_TO_WORK);
    assertThat(Availability.of("NOT_LOOKING")).isEqualTo(Availability.NOT_LOOKING);
    assertThat(Availability.of("lo-que-sea")).isEqualTo(Availability.UNSET);
    assertThat(Availability.of(null)).isEqualTo(Availability.UNSET);
  }
}
