package com.usac.jobseeker.matching.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La representación canónica: determinista, sin lo estructural, redactada y acotada. */
class CanonicalProfileComposerTest {

  private static final int ROOMY = 100_000;

  private static CandidateSignals signals(
      List<CandidateSignals.Skill> skills,
      List<CandidateSignals.Experience> experiences,
      List<CandidateSignals.Project> projects) {
    return new CandidateSignals(
        new CandidateProfileId(UUID.randomUUID()),
        new AccountId(UUID.randomUUID()),
        true,
        "Backend Developer",
        "GT",
        "REMOTE",
        "FULL_TIME",
        true,
        new CandidateSignals.Salary(new BigDecimal("3000"), new BigDecimal("4500"), "USD", "MONTH"),
        experiences,
        List.of(
            new CandidateSignals.Education(
                "Ingeniería en Sistemas", "BACHELOR", LocalDate.of(2019, 12, 1))),
        List.of("AWS Developer", "Scrum Master"),
        projects,
        skills,
        List.of(
            new CandidateSignals.Language("en", "B2"),
            new CandidateSignals.Language("es", "NATIVE")),
        List.of("GT", "MX"));
  }

  private static CandidateSignals typical() {
    return signals(
        List.of(
            new CandidateSignals.Skill("Java", 4),
            new CandidateSignals.Skill("SQL", null),
            new CandidateSignals.Skill("docker", 2)),
        List.of(
            new CandidateSignals.Experience(
                "Backend Engineer", LocalDate.of(2020, 1, 1), null, "Servicios de pagos"),
            new CandidateSignals.Experience(
                "Junior Dev", LocalDate.of(2018, 3, 1), LocalDate.of(2019, 12, 1), "Soporte")),
        List.of(new CandidateSignals.Project("Plataforma", "Una plataforma", "PERSONAL", "Líder")));
  }

  private static String text(CandidateSignals s) {
    return CanonicalProfileComposer.compose(s, ROOMY).text().value();
  }

  @Test
  void theSameSignalsGiveTheSameTextWhateverTheOrderTheyArriveIn() {
    CandidateSignals base = typical();
    String expected = text(base);
    Random random = new Random(7);

    for (int i = 0; i < 50; i++) {
      List<CandidateSignals.Skill> skills = new ArrayList<>(base.skills());
      List<CandidateSignals.Experience> experiences = new ArrayList<>(base.experiences());
      Collections.shuffle(skills, random);
      Collections.shuffle(experiences, random);

      assertThat(text(signals(skills, experiences, base.projects()))).isEqualTo(expected);
    }
  }

  @Test
  void theHashDoesNotChangeWhenOnlyTheStructuredDataChanges() {
    CandidateSignals base = typical();
    var other =
        new CandidateSignals(
            base.profileId(),
            base.accountId(),
            false,
            base.headline(),
            "MX",
            "ONSITE",
            "PART_TIME",
            false,
            new CandidateSignals.Salary(new BigDecimal("1"), null, "GTQ", "YEAR"),
            base.experiences(),
            base.education(),
            base.certifications(),
            base.projects(),
            base.skills(),
            base.languages(),
            List.of());

    assertThat(CanonicalProfileComposer.compose(other, ROOMY).text().contentHash())
        .isEqualTo(CanonicalProfileComposer.compose(base, ROOMY).text().contentHash());
  }

  @Test
  void aChangeInWhatIsEmbeddedChangesTheHash() {
    var more =
        signals(
            List.of(new CandidateSignals.Skill("Rust", 3)),
            typical().experiences(),
            typical().projects());

    assertThat(CanonicalProfileComposer.compose(more, ROOMY).text().contentHash())
        .isNotEqualTo(CanonicalProfileComposer.compose(typical(), ROOMY).text().contentHash());
  }

  @Test
  void itCarriesTheSemanticContentAndNoneOfTheStructuredFields() {
    String text = text(typical());

    assertThat(text)
        .contains(
            "Backend Developer",
            "Java (nivel 4)",
            "SQL",
            "Backend Engineer",
            "Servicios de pagos",
            "Ingeniería en Sistemas",
            "bachelor",
            "AWS Developer",
            "Plataforma",
            "en B2")
        .doesNotContain("REMOTE", "FULL_TIME", "USD", "3000", "GT", "MX", "4500");
  }

  @Test
  void freeTextIsRedactedBeforeItLeaves() {
    var withPii =
        signals(
            List.of(new CandidateSignals.Skill("Java", null)),
            List.of(
                new CandidateSignals.Experience(
                    "Dev",
                    LocalDate.of(2020, 1, 1),
                    null,
                    "Escríbeme a ana@example.com o al 5555-1234, mi perfil https://linkedin.com/in/ana")),
            List.of());

    var composed = CanonicalProfileComposer.compose(withPii, ROOMY);

    assertThat(composed.text().value())
        .doesNotContain("ana@example.com", "5555-1234", "linkedin.com")
        .contains("[correo]", "[teléfono]", "[enlace]");
    assertThat(composed.text().redactions()).isEqualTo(3);
  }

  @Test
  void anEmptyProfileGivesAnEmptyTextWithoutFailing() {
    var empty =
        new CandidateSignals(
            new CandidateProfileId(UUID.randomUUID()),
            new AccountId(UUID.randomUUID()),
            true,
            null,
            null,
            null,
            null,
            false,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    var composed = CanonicalProfileComposer.compose(empty, ROOMY);

    assertThat(composed.text().value()).isEmpty();
    assertThat(composed.truncated()).isFalse();
  }

  // ---- reducción registrada (ADR-0018, truncate=false) ----

  private static CandidateSignals big() {
    String paragraph = "palabra ".repeat(300);
    List<CandidateSignals.Experience> experiences = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      experiences.add(
          new CandidateSignals.Experience(
              "Puesto " + i, LocalDate.of(2010 + i, 1, 1), null, paragraph));
    }
    return signals(
        List.of(new CandidateSignals.Skill("Java", 4)),
        experiences,
        List.of(new CandidateSignals.Project("Proyecto", paragraph, "WORK", "Líder")));
  }

  @Test
  void aTextThatFitsIsNeverMarkedAsTruncated() {
    assertThat(CanonicalProfileComposer.compose(typical(), ROOMY).truncated()).isFalse();
  }

  @Test
  void aTextOverTheBudgetIsShortenedInStagesAndSaysSo() {
    int full = text(big()).length();

    var shortened = CanonicalProfileComposer.compose(big(), full - 1);
    var minimal = CanonicalProfileComposer.compose(big(), 3_000);
    var hard = CanonicalProfileComposer.compose(big(), 400);

    assertThat(shortened.truncated()).isTrue();
    assertThat(shortened.text().value().length()).isLessThan(full).isLessThanOrEqualTo(full - 1);
    assertThat(minimal.truncated()).isTrue();
    assertThat(minimal.text().value().length()).isLessThanOrEqualTo(3_000);
    assertThat(hard.truncated()).isTrue();
    assertThat(hard.text().value().length()).isLessThanOrEqualTo(400);
  }

  @Test
  void theReductionIsDeterministicToo() {
    assertThat(CanonicalProfileComposer.compose(big(), 2_000).text().contentHash())
        .isEqualTo(CanonicalProfileComposer.compose(big(), 2_000).text().contentHash());
  }
}
