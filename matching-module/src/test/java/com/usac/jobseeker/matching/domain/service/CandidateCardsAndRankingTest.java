package com.usac.jobseeker.matching.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.domain.model.Availability;
import com.usac.jobseeker.matching.domain.model.CandidateCard;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La tarjeta enmascarada (REQ-C-07) y el orden final de los sugeridos (BR-MATCH-07, BR-MATCH-09).
 */
class CandidateCardsAndRankingTest {

  private static final LocalDate TODAY = Signals.TODAY;

  // ---- tarjeta ----

  @Test
  void theHeadlineLosesAnyContactDetailItContainsAndNoPersonalFieldExists() {
    var candidate =
        Signals.candidate().headline("Backend, escríbeme a ana@correo.test o al 5555-0000").build();

    CandidateCard card = CandidateCards.of(candidate, TODAY);

    assertThat(card.headline())
        .doesNotContain("ana@correo.test")
        .doesNotContain("5555-0000")
        .contains("Backend");
    // El tipo no tiene dónde guardar nombre, foto, contacto, enlaces ni instituciones.
    assertThat(CandidateCard.class.getRecordComponents())
        .extracting(c -> c.getName())
        .containsExactly(
            "headline", "country", "yearsOfExperience", "highestEducation", "topSkills");
  }

  @Test
  void aBlankHeadlineBecomesNull() {
    assertThat(CandidateCards.of(Signals.candidate().headline("  ").build(), TODAY).headline())
        .isNull();
    assertThat(CandidateCards.of(Signals.candidate().build(), TODAY).headline()).isNull();
  }

  @Test
  void theTopSkillsAreAtMostFiveByLevelThenName() {
    var candidate =
        Signals.candidate()
            .skill("Zeta", 5)
            .skill("Alfa", 5)
            .skill("Beta", 4)
            .skill("Gamma", 3)
            .skill("Delta", 2)
            .skill("Épsilon", 1)
            .skill("Sin nivel", null)
            .skill(" ", 5)
            .build();

    assertThat(CandidateCards.of(candidate, TODAY).topSkills())
        .containsExactly("Alfa", "Zeta", "Beta", "Gamma", "Delta");
  }

  @Test
  void theCardCarriesYearsEducationAndCountryAndOnlyFinishedStudiesCount() {
    var candidate =
        Signals.candidate()
            .country("GT")
            .experience(LocalDate.of(2021, 11, 25), TODAY)
            .education("BACHELOR", LocalDate.of(2015, 1, 1))
            .education("MASTERS", null)
            .education("NO_EXISTE", LocalDate.of(2016, 1, 1))
            .build();

    CandidateCard card = CandidateCards.of(candidate, TODAY);

    assertThat(card.country()).isEqualTo("GT");
    assertThat(card.yearsOfExperience()).isEqualTo(5.0);
    assertThat(card.highestEducation()).isEqualTo("BACHELOR");
  }

  @Test
  void withoutFinishedStudiesThereIsNoEducation() {
    assertThat(
            CandidateCards.of(Signals.candidate().education("BACHELOR", null).build(), TODAY)
                .highestEducation())
        .isNull();
  }

  // ---- orden ----

  private static SuggestionRanking.Entry entry(double overall, Availability availability) {
    return new SuggestionRanking.Entry(UUID.randomUUID(), overall, availability);
  }

  private static List<SuggestionRanking.Entry> sorted(SuggestionRanking.Entry... entries) {
    return java.util.Arrays.stream(entries).sorted(SuggestionRanking.order(1.0)).toList();
  }

  @Test
  void aHigherDisplayedPercentageAlwaysWinsEvenOverOpenToWork() {
    var high = entry(80.4, Availability.NOT_LOOKING);
    var lowOpen = entry(70.0, Availability.OPEN_TO_WORK);

    assertThat(sorted(lowOpen, high)).containsExactly(high, lowOpen);
  }

  // BR-MATCH-07: entre quienes muestran el mismo porcentaje, OPEN_TO_WORK va primero.
  @Test
  void openToWorkBreaksTiesAtTheDisplayedPercentage() {
    var unset = entry(78.4, Availability.UNSET);
    var open = entry(77.6, Availability.OPEN_TO_WORK);
    var notLooking = entry(78.0, Availability.NOT_LOOKING);

    // 78,4 → 78; 77,6 → 78; 78,0 → 78: los tres muestran 78 %.
    assertThat(sorted(unset, notLooking, open).get(0)).isEqualTo(open);
  }

  @Test
  void withinTheSameAvailabilityTheUnroundedScoreAndThenTheIdentifierDecide() {
    var a = entry(78.4, Availability.UNSET);
    var b = entry(77.6, Availability.UNSET);

    assertThat(sorted(b, a)).containsExactly(a, b);
    var twinA = new SuggestionRanking.Entry(new UUID(0, 1), 50, Availability.UNSET);
    var twinB = new SuggestionRanking.Entry(new UUID(0, 2), 50, Availability.UNSET);
    assertThat(sorted(twinB, twinA)).containsExactly(twinA, twinB);
  }

  // La tolerancia (ADR-0030): con 3 puntos un OPEN_TO_WORK cercano adelanta a uno de mayor puntaje,
  // pero no a uno lejano.
  @Test
  void aWiderToleranceLetsOpenToWorkOvertakeANearbyScoreButNotADistantOne() {
    var higherUnset = entry(78.0, Availability.UNSET);
    var closeOpen = entry(76.0, Availability.OPEN_TO_WORK);
    var distantOpen = entry(60.0, Availability.OPEN_TO_WORK);

    var narrow = sorted(closeOpen, higherUnset);
    var wide =
        java.util.stream.Stream.of(higherUnset, closeOpen, distantOpen)
            .sorted(SuggestionRanking.order(3))
            .toList();

    assertThat(narrow).containsExactly(higherUnset, closeOpen);
    assertThat(wide).containsExactly(closeOpen, higherUnset, distantOpen);
  }

  // Sin saltos: a una décima de distancia no hay un corte que la prioridad pueda cruzar por
  // casualidad.
  @Test
  void theToleranceIsContinuousSoTheOrderIsTransitive() {
    var a = entry(77.4, Availability.UNSET);
    var b = entry(77.6, Availability.OPEN_TO_WORK);
    var c = entry(77.9, Availability.UNSET);

    // b adelanta a a y a c: la diferencia (0,3 como máximo) cabe en la tolerancia de un punto.
    assertThat(sorted(c, a, b)).containsExactly(b, c, a);
  }

  // R1 del ADR-0030: con una diferencia exactamente igual a la tolerancia las claves empatan y gana
  // el
  // de mayor puntaje; la prioridad es para quien le saca MENOS de la tolerancia.
  @Test
  void aDifferenceExactlyEqualToTheToleranceDoesNotOvertake() {
    var higher = entry(78.0, Availability.UNSET);
    var exactly = entry(77.0, Availability.OPEN_TO_WORK);
    var justInside = entry(77.2, Availability.OPEN_TO_WORK);

    assertThat(sorted(exactly, higher)).containsExactly(higher, exactly);
    assertThat(sorted(higher, justInside)).containsExactly(justInside, higher);
  }

  @Test
  void aZeroToleranceMeansNoPriorityAtAll() {
    var higher = entry(70.001, Availability.UNSET);
    var open = entry(70.0, Availability.OPEN_TO_WORK);

    assertThat(java.util.stream.Stream.of(open, higher).sorted(SuggestionRanking.order(0)).toList())
        .containsExactly(higher, open);
  }

  @Test
  void theToleranceMustStayWithinItsBounds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> SuggestionRanking.order(-0.1))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> SuggestionRanking.order(SuggestionRanking.MAX_TOLERANCE + 0.1))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> SuggestionRanking.order(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
