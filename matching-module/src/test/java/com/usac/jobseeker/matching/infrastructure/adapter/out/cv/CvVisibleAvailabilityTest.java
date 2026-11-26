package com.usac.jobseeker.matching.infrastructure.adapter.out.cv;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.GetVisibleAvailabilitiesQuery;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.GetVisibleAvailabilityQuery;
import com.usac.jobseeker.matching.domain.model.Availability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * `matching` pregunta a `cv` por la disponibilidad que ve el reclutador y no reimplementa la
 * exclusión.
 */
class CvVisibleAvailabilityTest {

  private final AccountId viewer = new AccountId(UUID.randomUUID());
  private final AccountId open = new AccountId(UUID.randomUUID());
  private final AccountId excluding = new AccountId(UUID.randomUUID());
  private final List<GetVisibleAvailabilityQuery> single = new ArrayList<>();
  private final List<GetVisibleAvailabilitiesQuery> batch = new ArrayList<>();

  private final CvVisibleAvailability adapter =
      new CvVisibleAvailability(
          query -> {
            single.add(query);
            return query.candidate().equals(excluding) ? "UNSET" : "OPEN_TO_WORK";
          },
          query -> {
            batch.add(query);
            return Map.of(open, "OPEN_TO_WORK", excluding, "UNSET");
          });

  @Test
  void theSingleAnswerIsTheNameCvGivesTranslated() {
    assertThat(adapter.visibleTo(viewer, open)).isEqualTo(Availability.OPEN_TO_WORK);
    assertThat(adapter.visibleTo(viewer, excluding)).isEqualTo(Availability.UNSET);
    assertThat(single).extracting(GetVisibleAvailabilityQuery::viewer).containsOnly(viewer);
  }

  // TD-039: todos con una consulta, y quien excluyó al reclutador llega como UNSET sin que matching
  // lo decida.
  @Test
  void manyCandidatesAreAskedOnceAndTranslated() {
    var all = adapter.visibleToAll(viewer, List.of(open, excluding));

    assertThat(all)
        .containsEntry(open, Availability.OPEN_TO_WORK)
        .containsEntry(excluding, Availability.UNSET);
    assertThat(batch)
        .singleElement()
        .satisfies(
            q -> {
              assertThat(q.viewer()).isEqualTo(viewer);
              assertThat(q.candidates()).containsExactly(open, excluding);
            });
    assertThat(single).isEmpty();
  }

  @Test
  void anUnknownStatusNameIsUnset() {
    var odd = new CvVisibleAvailability(q -> "RARO", q -> Map.of(open, "RARO"));

    assertThat(odd.visibleTo(viewer, open)).isEqualTo(Availability.UNSET);
    assertThat(odd.visibleToAll(viewer, List.of(open))).containsEntry(open, Availability.UNSET);
  }
}
