package com.usac.jobseeker.matching.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La representación canónica de una vacante: determinista, sin lo estructural, redactada y acotada.
 */
class CanonicalJobComposerTest {

  private static final int ROOMY = 100_000;

  private static JobSignals.Requirement skill(String name, boolean mandatory, Integer level) {
    return new JobSignals.Requirement("SKILL", mandatory, name, level, null, null, null, null);
  }

  private static JobSignals signals(
      String title, String description, List<JobSignals.Requirement> r) {
    return new JobSignals(
        new JobPostingId(UUID.randomUUID()), 3, true, false, title, description, r);
  }

  private static String text(JobSignals signals, int max) {
    return CanonicalJobComposer.compose(signals, max).text().value();
  }

  // La modalidad, el país y el salario viajan en las señales para puntuar, pero no son semántica:
  // no
  // entran al texto que se embebe ni, por tanto, salen hacia el modelo (ADR-0017, decisión 2).
  @Test
  void modalityCountryAndSalaryAreInTheSignalsButNeverInTheEmbeddedText() {
    var withStructure =
        new JobSignals(
            new JobPostingId(UUID.randomUUID()),
            3,
            true,
            false,
            "Ingeniero Backend",
            "Construirás servicios.",
            "ONSITE",
            "GT",
            new JobSignals.Salary(
                new java.math.BigDecimal("98765"),
                new java.math.BigDecimal("123456"),
                "GTQ",
                "MONTH"),
            List.of(skill("Java", true, 3)));

    String text = text(withStructure, ROOMY);

    assertThat(text)
        .doesNotContain("ONSITE")
        .doesNotContain("GT")
        .doesNotContain("98765")
        .doesNotContain("GTQ");
    assertThat(text(withStructure, ROOMY))
        .isEqualTo(
            text(
                signals(
                    "Ingeniero Backend", "Construirás servicios.", List.of(skill("Java", true, 3))),
                ROOMY));
  }

  @Test
  void theTextCarriesTitleDescriptionAndEveryKindOfRequirement() {
    var signals =
        signals(
            "Ingeniero Backend",
            "Construirás servicios.",
            List.of(
                skill("Java", true, 3),
                skill("Docker", false, null),
                new JobSignals.Requirement(
                    "EDUCATION", false, null, null, "BACHELOR", null, null, null),
                new JobSignals.Requirement(
                    "EXPERIENCE_YEARS", true, null, null, null, 5, null, null),
                new JobSignals.Requirement("LANGUAGE", false, null, null, null, null, "en", "B2")));

    String composed = text(signals, ROOMY);

    assertThat(composed)
        .contains("Vacante: Ingeniero Backend")
        .contains("Descripción: Construirás servicios.")
        .contains("Habilidades obligatorias: Java (nivel 3)")
        .contains("Habilidades deseables: Docker")
        .contains("Formación mínima: bachelor")
        .contains("Experiencia mínima: 5 años")
        .contains("Idiomas: en B2");
  }

  // BR-MATCH-03: el mismo contenido da siempre el mismo texto, sea cual sea el orden de entrega.
  @Test
  void theOrderInWhichRequirementsArriveDoesNotChangeTheText() {
    var forward =
        List.of(skill("Java", true, null), skill("sql", true, null), skill("Go", true, null));
    var backward = new java.util.ArrayList<>(forward);
    Collections.reverse(backward);

    assertThat(text(signals("T", "D", forward), ROOMY))
        .isEqualTo(text(signals("T", "D", backward), ROOMY));
  }

  @Test
  void skillsAreSortedWithoutRegardToCase() {
    String composed =
        text(
            signals("T", "D", List.of(skill("sql", true, null), skill("Java", true, null))), ROOMY);

    assertThat(composed).contains("Habilidades obligatorias: Java, sql");
  }

  // Lo estructural es de otras dimensiones del puntaje: cambiarlo no debe recalcular el vector.
  @Test
  void aSectionWithNothingInItIsOmitted() {
    String composed = text(signals("Solo título", "Algo", List.of()), ROOMY);

    assertThat(composed)
        .doesNotContain("Habilidades")
        .doesNotContain("Formación")
        .doesNotContain("Idiomas");
  }

  @Test
  void theMandatoryAndDesirableSkillsAreSeparate() {
    String composed =
        text(
            signals("T", "D", List.of(skill("Java", true, null), skill("Go", false, null))), ROOMY);

    assertThat(composed)
        .contains("Habilidades obligatorias: Java")
        .contains("Habilidades deseables: Go")
        .doesNotContain("obligatorias: Java, Go");
  }

  // El texto sale como EmbeddableText, que redacta: un contacto en la descripción no llega al
  // modelo.
  @Test
  void contactDataInTheDescriptionIsRedactedBeforeItLeaves() {
    String composed =
        text(signals("T", "Escribe a reclutador@empresa.com o al 5555-1234", List.of()), ROOMY);

    assertThat(composed).doesNotContain("reclutador@empresa.com").contains("[correo]");
  }

  @Test
  void theHashChangesOnlyWhenTheContentChanges() {
    var a =
        CanonicalJobComposer.compose(signals("T", "D", List.of(skill("Java", true, null))), ROOMY);
    var same =
        CanonicalJobComposer.compose(signals("T", "D", List.of(skill("Java", true, null))), ROOMY);
    var other =
        CanonicalJobComposer.compose(signals("T", "D2", List.of(skill("Java", true, null))), ROOMY);

    assertThat(a.text().contentHash()).isEqualTo(same.text().contentHash());
    assertThat(a.text().contentHash()).isNotEqualTo(other.text().contentHash());
  }

  // ---- acotada ----

  @Test
  void aTextThatFitsIsNotMarkedTruncated() {
    assertThat(CanonicalJobComposer.compose(signals("T", "D", List.of()), ROOMY).truncated())
        .isFalse();
  }

  // Primero se acorta la descripción, que es lo largo, y los requisitos se conservan.
  @Test
  void aLongDescriptionIsShortenedFirstAndTheRequirementsSurvive() {
    String longDescription = "palabra ".repeat(1000);
    var signals = signals("T", longDescription, List.of(skill("Java", true, null)));

    var composed = CanonicalJobComposer.compose(signals, 2500);

    assertThat(composed.truncated()).isTrue();
    assertThat(composed.text().value().length()).isLessThanOrEqualTo(2500);
    assertThat(composed.text().value()).contains("Habilidades obligatorias: Java");
  }

  @Test
  void whenEvenTheShortDescriptionDoesNotFitTheEndIsCutAtAWord() {
    var composed =
        CanonicalJobComposer.compose(
            signals("T", "palabra ".repeat(1000), List.of(skill("Java", true, null))), 200);

    assertThat(composed.truncated()).isTrue();
    assertThat(composed.text().value().length()).isLessThanOrEqualTo(200);
    assertThat(composed.text().value()).doesNotEndWith(" ");
  }

  @Test
  void aWordLongerThanTheBudgetIsCutAtTheLimit() {
    var composed = CanonicalJobComposer.compose(signals("T", "x".repeat(5000), List.of()), 100);

    assertThat(composed.truncated()).isTrue();
    assertThat(composed.text().value().length()).isLessThanOrEqualTo(100);
  }
}
