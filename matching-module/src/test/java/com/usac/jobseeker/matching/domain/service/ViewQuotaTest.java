package com.usac.jobseeker.matching.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La cuota de perfiles únicos por ventana (REQ-R-11): lo que cuesta es cada perfil distinto. */
class ViewQuotaTest {

  private static CandidateProfileId id() {
    return new CandidateProfileId(UUID.randomUUID());
  }

  private final CandidateProfileId a = id();
  private final CandidateProfileId b = id();
  private final CandidateProfileId c = id();
  private final CandidateProfileId d = id();

  @Test
  void everythingFitsWhenThereIsRoomAndTheRemainderCountsOnlyNewProfiles() {
    var allowance = ViewQuota.apply(List.of(a, b, c), Set.of(), 10);

    assertThat(allowance.allowed()).containsExactly(a, b, c);
    assertThat(allowance.remaining()).isEqualTo(7);
    assertThat(allowance.exhausted()).isFalse();
  }

  // Volver a ver lo ya visto es gratis: paginar o refrescar la misma lista no gasta cuota.
  @Test
  void aProfileAlreadyViewedInTheWindowIsFree() {
    var allowance = ViewQuota.apply(List.of(a, b), Set.of(a, b), 2);

    assertThat(allowance.allowed()).containsExactly(a, b);
    assertThat(allowance.remaining()).isZero();
    assertThat(allowance.exhausted()).isFalse();
  }

  @Test
  void whatRemainsIsTheQuotaMinusTheUniqueProfilesAlreadyViewed() {
    var allowance = ViewQuota.apply(List.of(a), Set.of(b, c), 5);

    assertThat(allowance.remaining()).isEqualTo(2);
  }

  // Si no caben todos los nuevos, se sirven los que caben en el orden del ranking.
  @Test
  void whenNotAllNewProfilesFitTheBestRankedOnesAreServed() {
    var allowance = ViewQuota.apply(List.of(a, b, c, d), Set.of(), 2);

    assertThat(allowance.allowed()).containsExactly(a, b);
    assertThat(allowance.remaining()).isZero();
    assertThat(allowance.exhausted()).isFalse();
  }

  @Test
  void seenProfilesStayAndNewOnesAreTrimmedKeepingTheRankingOrder() {
    var allowance = ViewQuota.apply(List.of(a, b, c, d), Set.of(b, d, id()), 4);

    // Quedaba 1 de cuota: entra `a` (nuevo, el mejor), `b` y `d` son gratis, `c` ya no cabe.
    assertThat(allowance.allowed()).containsExactly(a, b, d);
    assertThat(allowance.remaining()).isZero();
  }

  // Agotada solo si había candidatos y no se pudo mostrar ninguno.
  @Test
  void aPageOfOnlyNewProfilesWithNoQuotaLeftIsExhausted() {
    var allowance = ViewQuota.apply(List.of(a, b), Set.of(c, d), 2);

    assertThat(allowance.allowed()).isEmpty();
    assertThat(allowance.exhausted()).isTrue();
  }

  @Test
  void aZeroQuotaShowsNothingNewButStillAllowsWhatWasAlreadySeen() {
    assertThat(ViewQuota.apply(List.of(a), Set.of(), 0).exhausted()).isTrue();
    assertThat(ViewQuota.apply(List.of(a), Set.of(a), 0).allowed()).containsExactly(a);
  }

  @Test
  void anEmptyPageIsNeverExhausted() {
    var allowance = ViewQuota.apply(List.of(), Set.of(a), 1);

    assertThat(allowance.allowed()).isEmpty();
    assertThat(allowance.exhausted()).isFalse();
    assertThat(allowance.remaining()).isZero();
  }

  // Más visto que la cuota (se bajó la configuración): no queda nada y no es un número negativo.
  @Test
  void viewingMoreThanTheQuotaNeverGivesANegativeRemainder() {
    var allowance = ViewQuota.apply(List.of(a), Set.of(b, c, d), 2);

    assertThat(allowance.remaining()).isZero();
    assertThat(allowance.exhausted()).isTrue();
  }

  @Test
  void aNegativeQuotaIsRefused() {
    List<CandidateProfileId> ranked = List.of(a);
    Set<CandidateProfileId> viewed = Set.of();

    assertThatThrownBy(() -> ViewQuota.apply(ranked, viewed, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theAllowedListIsACopy() {
    var allowance = ViewQuota.apply(List.of(a), Set.of(), 5);

    assertThatThrownBy(() -> allowance.allowed().add(b))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
