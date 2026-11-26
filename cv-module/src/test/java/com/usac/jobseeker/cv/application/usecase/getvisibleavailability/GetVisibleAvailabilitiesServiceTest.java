package com.usac.jobseeker.cv.application.usecase.getvisibleavailability;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TD-039: la disponibilidad de varios candidatos con la **misma regla** que la individual
 * (BR-CAND-05, BR-VIS-06): quien excluyó al reclutador se ve `UNSET`, el propio candidato ve el
 * suyo, y quien no tiene perfil es `UNSET`; con una consulta de perfiles y una de exclusiones.
 */
class GetVisibleAvailabilitiesServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  private final Map<AccountId, CandidateProfile> profiles = new HashMap<>();
  private final Set<String> exclusions = new HashSet<>();
  private final List<String> calls = new ArrayList<>();

  private final GetVisibleAvailabilityService service =
      new GetVisibleAvailabilityService(
          new CandidateProfileReadPort() {
            public Optional<CandidateProfile> findByAccountId(AccountId id) {
              calls.add("profiles:one");
              return Optional.ofNullable(profiles.get(id));
            }

            public Map<AccountId, CandidateProfile> findAllByAccountIds(Collection<AccountId> ids) {
              calls.add("profiles:batch");
              Map<AccountId, CandidateProfile> found = new HashMap<>(profiles);
              found.keySet().retainAll(ids);
              return found;
            }
          },
          new AvailabilityExclusionLookupPort() {
            public boolean isExcluded(CandidateProfileId profile, AccountId account) {
              calls.add("exclusions:one");
              return exclusions.contains(profile.value() + ":" + account.value());
            }

            public Set<CandidateProfileId> excludedAmong(
                Collection<CandidateProfileId> ids, AccountId account) {
              calls.add("exclusions:batch");
              Set<CandidateProfileId> excluded = new HashSet<>();
              ids.forEach(
                  id -> {
                    if (exclusions.contains(id.value() + ":" + account.value())) {
                      excluded.add(id);
                    }
                  });
              return excluded;
            }
          });

  private AccountId candidate(AvailabilityStatus status) {
    AccountId account = new AccountId(UUID.randomUUID());
    profiles.put(
        account,
        new CandidateProfile(
            new CandidateProfileId(UUID.randomUUID()),
            account,
            CandidateDetails.of("x@y.co", null, null, null, null, false, null, null, null, null),
            false,
            status,
            1,
            T0));
    return account;
  }

  private Map<AccountId, String> ask(AccountId viewer, AccountId... candidates) {
    return service.execute(new GetVisibleAvailabilitiesQuery(viewer, List.of(candidates)));
  }

  @Test
  void aCandidateWhoExcludedTheViewerIsUnsetAndTheOthersShowTheirRealStatus() {
    var viewer = new AccountId(UUID.randomUUID());
    var open = candidate(AvailabilityStatus.OPEN_TO_WORK);
    var excluding = candidate(AvailabilityStatus.OPEN_TO_WORK);
    var notLooking = candidate(AvailabilityStatus.NOT_LOOKING);
    exclusions.add(profiles.get(excluding).id().value() + ":" + viewer.value());

    var visible = ask(viewer, open, excluding, notLooking);

    assertThat(visible)
        .containsEntry(open, "OPEN_TO_WORK")
        .containsEntry(excluding, "UNSET")
        .containsEntry(notLooking, "NOT_LOOKING");
  }

  @Test
  void aCandidateWithoutAProfileIsUnsetAndEveryRequestedAccountIsAnswered() {
    var viewer = new AccountId(UUID.randomUUID());
    var unknown = new AccountId(UUID.randomUUID());

    assertThat(ask(viewer, unknown)).containsOnlyKeys(unknown).containsEntry(unknown, "UNSET");
  }

  // El candidato siempre ve su propio estado, aunque se hubiera excluido a sí mismo por error.
  @Test
  void theOwnerAlwaysSeesItsOwnStatus() {
    var me = candidate(AvailabilityStatus.OPEN_TO_WORK);
    exclusions.add(profiles.get(me).id().value() + ":" + me.value());

    assertThat(ask(me, me)).containsEntry(me, "OPEN_TO_WORK");
  }

  @Test
  void theBatchGivesTheSameAnswerAsTheSingleRead() {
    var viewer = new AccountId(UUID.randomUUID());
    var a = candidate(AvailabilityStatus.OPEN_TO_WORK);
    var b = candidate(AvailabilityStatus.NOT_LOOKING);
    exclusions.add(profiles.get(b).id().value() + ":" + viewer.value());

    var batch = ask(viewer, a, b);

    for (AccountId id : List.of(a, b)) {
      assertThat(batch.get(id))
          .isEqualTo(service.execute(new GetVisibleAvailabilityQuery(id, viewer)));
    }
  }

  @Test
  void profilesAndExclusionsAreEachAskedOncePerBatch() {
    var viewer = new AccountId(UUID.randomUUID());
    var many = new ArrayList<AccountId>();
    for (int i = 0; i < 30; i++) {
      many.add(candidate(AvailabilityStatus.UNSET));
    }
    calls.clear();

    service.execute(new GetVisibleAvailabilitiesQuery(viewer, many));

    assertThat(calls).containsExactly("profiles:batch", "exclusions:batch");
  }
}
