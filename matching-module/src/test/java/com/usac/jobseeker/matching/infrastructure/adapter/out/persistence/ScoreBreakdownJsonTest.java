package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.matching.domain.model.DataStatus;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import com.usac.jobseeker.matching.domain.model.ScoreBreakdown;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * El desglose se guarda como JSON sin perder nada, y uno anonimizado (`{}`) se lee sin dimensiones.
 */
class ScoreBreakdownJsonTest {

  private static ScoreBreakdown breakdown() {
    return new ScoreBreakdown(
        List.of(
            new DimensionScore(
                Dimension.SKILLS,
                DataStatus.AVAILABLE,
                0.75,
                0.6,
                "Cubre 3 de 4 habilidades requeridas; faltan: Go."),
            new DimensionScore(
                Dimension.SALARY,
                DataStatus.NOT_COMPARABLE,
                0,
                0,
                "Las monedas difieren (USD y GTQ): no se compara.")),
        true,
        0.75,
        0.62,
        0.3,
        0.8);
  }

  @Test
  void aBreakdownSurvivesAWriteAndARead() {
    var original = breakdown();

    var read = ScoreBreakdownJson.read(ScoreBreakdownJson.write(original), 0.8);

    assertThat(read).isEqualTo(original);
  }

  @Test
  void theExplanationsKeepTheirAccentsAndPunctuation() {
    var read = ScoreBreakdownJson.read(ScoreBreakdownJson.write(breakdown()), 0.8);

    assertThat(read.dimensions().get(1).explanation())
        .isEqualTo("Las monedas difieren (USD y GTQ): no se compara.");
  }

  // BR-DATA-05: lo derivado del perfil se vacía; el par, el puntaje y la versión se conservan.
  @Test
  void anAnonymizedBreakdownReadsAsHavingNoDimensionsButKeepsTheRowCompleteness() {
    var read = ScoreBreakdownJson.read("{}", 0.8);

    assertThat(read.dimensions()).isEmpty();
    assertThat(read.completeness()).isEqualTo(0.8);
    assertThat(read.structuredPresent()).isFalse();
  }

  @Test
  void aCorruptStoredBreakdownIsAnErrorNotASilentEmptyOne() {
    assertThatThrownBy(() -> ScoreBreakdownJson.read("{no es json", 0.5))
        .isInstanceOf(IllegalStateException.class);
  }
}
