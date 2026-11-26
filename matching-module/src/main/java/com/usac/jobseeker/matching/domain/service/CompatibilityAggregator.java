package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.AlgorithmVersion;
import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.DataStatus;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.DimensionResult;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreBreakdown;
import com.usac.jobseeker.matching.domain.model.Weights;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Combina las seis dimensiones y la afinidad semántica en un puntaje explicable (BR-MATCH-01/02).
 * Es **aritmética pura** sobre señales y un coseno ya calculados: no llama a ningún modelo
 * (ADR-0017, decisión 1) y es determinista, así que el mismo par con la misma versión da siempre lo
 * mismo.
 *
 * <p>`overall = 100 · ((1 − α) · estructurado + α · semántica calibrada)`, con `α` en el desglose.
 * Una dimensión que no está disponible se excluye y los pesos se renormalizan entre las demás
 * (decisión 6): lo que la vacante no define no cuenta y no baja la completitud; lo que el candidato
 * no declaró sí la baja. Declarar un dato que coincide nunca baja el puntaje frente a omitirlo,
 * porque sumar una dimensión con puntaje 1 no puede bajar un promedio ponderado de valores ≤ 1
 * (control C3, probado como propiedad).
 */
public final class CompatibilityAggregator {

  /** Versión del código del algoritmo; cambia con cualquier regla de las dimensiones. */
  public static final String CODE_VERSION = "1";

  private CompatibilityAggregator() {}

  public static CompatibilityScore score(
      CandidateSignals candidate,
      JobSignals job,
      double cosine,
      Weights weights,
      Calibration calibration,
      LocalDate today) {
    Map<Dimension, DimensionResult> results = new EnumMap<>(Dimension.class);
    results.put(
        Dimension.SKILLS, SkillDimensions.skills(candidate, job, weights.mandatoryFactor()));
    results.put(Dimension.EXPERIENCE, ProfileDimensions.experience(candidate, job, today));
    results.put(Dimension.EDUCATION, ProfileDimensions.education(candidate, job, today));
    results.put(Dimension.LOCATION_MODALITY, FitDimensions.locationModality(candidate, job));
    results.put(Dimension.SALARY, FitDimensions.salary(candidate, job));
    results.put(
        Dimension.LANGUAGES, SkillDimensions.languages(candidate, job, weights.mandatoryFactor()));

    double availableWeight = 0;
    double applicableWeight = 0;
    double weighted = 0;
    for (Dimension dimension : Dimension.values()) {
      DimensionResult result = results.get(dimension);
      if (result.status() != DataStatus.NOT_APPLICABLE) {
        applicableWeight += weights.weight(dimension);
      }
      if (result.status() == DataStatus.AVAILABLE) {
        availableWeight += weights.weight(dimension);
        weighted += weights.weight(dimension) * result.score();
      }
    }
    boolean structuredPresent = availableWeight > 0;
    double structured = structuredPresent ? weighted / availableWeight : 0;
    // Si la vacante no define nada medible, el candidato no omitió nada: la completitud es 1.
    double completeness = applicableWeight == 0 ? 1 : availableWeight / applicableWeight;
    double semantic = calibration.apply(cosine);
    double alpha = structuredPresent ? weights.alpha() : 1;
    double overall = 100 * ((1 - alpha) * structured + alpha * semantic);

    List<DimensionScore> rows = new ArrayList<>();
    for (Dimension dimension : Dimension.values()) {
      DimensionResult result = results.get(dimension);
      double weight =
          result.status() == DataStatus.AVAILABLE ? weights.weight(dimension) / availableWeight : 0;
      rows.add(
          new DimensionScore(
              dimension, result.status(), result.score(), weight, result.explanation()));
    }
    ScoreBreakdown breakdown =
        new ScoreBreakdown(rows, structuredPresent, structured, semantic, alpha, completeness);
    AlgorithmVersion version =
        new AlgorithmVersion(CODE_VERSION, calibration.model(), weights.hash(), calibration.hash());
    return new CompatibilityScore(
        Math.clamp(overall, 0.0, 100.0), breakdown, version, calibration.calibrated());
  }
}
