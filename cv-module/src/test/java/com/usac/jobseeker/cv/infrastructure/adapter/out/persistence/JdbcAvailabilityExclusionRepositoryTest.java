package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Descubribilidad, disponibilidad y lista de exclusión contra las tablas reales (Postgres 17): cada
 * escritura toca solo su columna, la versión es el bloqueo optimista y la unicidad de la lista es
 * su control de concurrencia.
 */
class JdbcAvailabilityExclusionRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcCandidateProfileRepository profiles = new JdbcCandidateProfileRepository(jdbc);
  private final JdbcAvailabilityExclusionRepository exclusions =
      new JdbcAvailabilityExclusionRepository(jdbc);

  private AccountId account() {
    AccountId account = new AccountId(UUID.randomUUID());
    jdbc.sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
    return account;
  }

  private CandidateProfile profile() {
    var details =
        CandidateDetails.of("a@b.co", null, null, null, null, null, null, null, null, null);
    var created =
        CandidateProfile.create(new CandidateProfileId(UUID.randomUUID()), account(), details, T0);
    assertThat(profiles.insertIfAbsent(created)).isTrue();
    return created;
  }

  // ---- las dos columnas del perfil ----

  @Test
  void discoverabilityIsWrittenAloneAndOnlyWithTheExpectedVersion() {
    var saved = profile();
    var hidden = saved.withDiscoverable(false, T0.plusSeconds(1));

    assertThat(profiles.updateDiscoverabilityIfVersion(hidden, 9)).isFalse();
    assertThat(profiles.updateDiscoverabilityIfVersion(hidden, 1)).isTrue();
    assertThat(profiles.updateDiscoverabilityIfVersion(hidden, 1)).isFalse();

    var read = profiles.findByAccountId(saved.accountId()).orElseThrow();
    assertThat(read.discoverable()).isFalse();
    assertThat(read.availability()).isEqualTo(AvailabilityStatus.UNSET);
    assertThat(read.details()).isEqualTo(saved.details());
    assertThat(read.version()).isEqualTo(2);
  }

  @Test
  void availabilityIsWrittenAloneAndDoesNotTouchDiscoverability() {
    var saved = profile();
    jdbc.sql("update candidate_profiles set discoverable = false where id = :id")
        .param("id", saved.id().value())
        .update();
    var open = saved.withAvailability(AvailabilityStatus.OPEN_TO_WORK, T0.plusSeconds(1));

    assertThat(profiles.updateAvailabilityIfVersion(open, 1)).isTrue();

    var read = profiles.findByAccountId(saved.accountId()).orElseThrow();
    assertThat(read.availability()).isEqualTo(AvailabilityStatus.OPEN_TO_WORK);
    assertThat(read.discoverable()).isFalse();
  }

  // ---- la lista de exclusión ----

  @Test
  void anExclusionIsAddedListedNewestFirstCheckedAndRemoved() {
    var owner = profile().id();
    var first = account();
    var second = account();

    assertThat(exclusions.insert(owner, new AvailabilityExclusion(first, T0))).isTrue();
    assertThat(exclusions.insert(owner, new AvailabilityExclusion(second, T0.plusSeconds(5))))
        .isTrue();

    assertThat(exclusions.list(owner))
        .extracting(AvailabilityExclusion::excludedAccount)
        .containsExactly(second, first);
    assertThat(exclusions.count(owner)).isEqualTo(2);
    assertThat(exclusions.isExcluded(owner, first)).isTrue();
    assertThat(exclusions.delete(owner, first)).isTrue();
    assertThat(exclusions.delete(owner, first)).isFalse();
    assertThat(exclusions.isExcluded(owner, first)).isFalse();
    assertThat(exclusions.list(owner).get(0).createdAt()).isEqualTo(T0.plusSeconds(5));
  }

  @Test
  void theSameAccountCannotBeAddedTwiceAndTheSecondInsertSaysSo() {
    var owner = profile().id();
    var excluded = account();

    assertThat(exclusions.insert(owner, new AvailabilityExclusion(excluded, T0))).isTrue();
    assertThat(exclusions.insert(owner, new AvailabilityExclusion(excluded, T0.plusSeconds(1))))
        .isFalse();

    assertThat(exclusions.count(owner)).isEqualTo(1);
    assertThat(exclusions.list(owner).get(0).createdAt()).isEqualTo(T0);
  }

  @Test
  void theListOfOneCandidateNeverShowsInAnothers() {
    var mine = profile().id();
    var theirs = profile().id();
    var excluded = account();
    exclusions.insert(mine, new AvailabilityExclusion(excluded, T0));

    assertThat(exclusions.isExcluded(theirs, excluded)).isFalse();
    assertThat(exclusions.list(theirs)).isEmpty();
    assertThat(exclusions.delete(theirs, excluded)).isFalse();
  }

  @Test
  void theDatabaseRejectsAnUnknownAccountOrAProfile() {
    var owner = profile().id();
    var ghost = new AvailabilityExclusion(new AccountId(UUID.randomUUID()), T0);
    var real = new AvailabilityExclusion(account(), T0);
    var noProfile = new CandidateProfileId(UUID.randomUUID());

    assertThatThrownBy(() -> exclusions.insert(owner, ghost))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> exclusions.insert(noProfile, real))
        .isInstanceOf(DataAccessException.class);
  }
}
