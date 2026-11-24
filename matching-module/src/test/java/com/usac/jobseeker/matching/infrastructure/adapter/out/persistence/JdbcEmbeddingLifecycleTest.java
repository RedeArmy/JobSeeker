package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La lápida de la anonimización contra Postgres real (BR-DATA-05, TD-019): después de anonimizar
 * una cuenta, ninguna escritura —ni la de un cálculo que ya estaba en curso— puede dejar un vector
 * o una descubribilidad suyos, en cualquiera de los dos órdenes en que pueden llegar.
 */
class JdbcEmbeddingLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

  private final JdbcClient jdbc = JdbcClient.create(MatchingPostgres.dataSource());
  private final JdbcEmbeddingRepository store = new JdbcEmbeddingRepository(jdbc);
  private final AccountId account = new AccountId(UUID.randomUUID());
  private final CandidateProfileId profile = new CandidateProfileId(UUID.randomUUID());

  private static EntityEmbedding embedding() {
    float[] values = new float[1024];
    values[0] = 1f;
    return new EntityEmbedding(
        EmbeddingVector.of(values), "qwen3-embedding:0.6b", "c".repeat(64), false, NOW);
  }

  private boolean hasVector() {
    return store.findProfileEmbedding(profile).isPresent();
  }

  private boolean discoverable() {
    return store.findProfileState(profile).orElseThrow().discoverable();
  }

  @Test
  void aRegisteredAccountIsFoundByAccountEvenWhenCvNoLongerSaysWhichProfileItWas() {
    assertThat(store.registerProfile(account, profile)).isTrue();

    assertThat(store.findProfileIdByAccount(account)).contains(profile);
  }

  @Test
  void registeringTwiceKeepsTheSameProfile() {
    store.registerProfile(account, profile);

    assertThat(store.registerProfile(account, new CandidateProfileId(UUID.randomUUID()))).isTrue();

    assertThat(store.findProfileIdByAccount(account)).contains(profile);
  }

  @Test
  void anUnknownAccountHasNoProfile() {
    assertThat(store.findProfileIdByAccount(new AccountId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void anonymizingClearsTheVectorAndTheFlagAndForgetsTheAccountAtOnce() {
    store.registerProfile(account, profile);
    store.setProfileDiscoverable(profile, true);
    store.saveProfileEmbedding(profile, true, embedding());

    assertThat(store.anonymizeProfile(account)).isTrue();

    assertThat(hasVector()).isFalse();
    assertThat(discoverable()).isFalse();
    assertThat(store.findProfileIdByAccount(account)).isEmpty();
  }

  @Test
  void anonymizingTwiceChangesNothingTheSecondTime() {
    store.registerProfile(account, profile);
    store.saveProfileEmbedding(profile, true, embedding());
    store.anonymizeProfile(account);

    assertThat(store.anonymizeProfile(account)).isFalse();
  }

  // El orden que hace daño: el cálculo leyó el perfil antes de la anonimización y escribe después.
  @Test
  void aWriteThatArrivesAfterTheAnonymizationLeavesNothing() {
    store.registerProfile(account, profile);
    store.setProfileDiscoverable(profile, true);
    store.anonymizeProfile(account);

    assertThat(store.saveProfileEmbedding(profile, true, embedding())).isFalse();
    assertThat(store.setProfileDiscoverable(profile, true)).isFalse();
    assertThat(store.registerProfile(account, profile)).isFalse();

    assertThat(hasVector()).isFalse();
    assertThat(discoverable()).isFalse();
  }

  // El otro orden: la anonimización llega para una cuenta que matching todavía no conocía.
  @Test
  void anAccountAnonymizedBeforeMatchingKnewItCannotBeRegisteredNorEmbeddedLater() {
    assertThat(store.anonymizeProfile(account)).isTrue();

    assertThat(store.registerProfile(account, profile)).isFalse();
    assertThat(store.saveProfileEmbedding(profile, true, embedding())).isFalse();
    assertThat(store.setProfileDiscoverable(profile, true)).isFalse();
    assertThat(store.findProfileState(profile)).isEmpty();
  }

  @Test
  void anotherAccountIsNeverAffected() {
    var other = new AccountId(UUID.randomUUID());
    var otherProfile = new CandidateProfileId(UUID.randomUUID());
    store.registerProfile(other, otherProfile);
    store.saveProfileEmbedding(otherProfile, true, embedding());
    store.registerProfile(account, profile);

    store.anonymizeProfile(account);

    assertThat(store.findProfileEmbedding(otherProfile)).isPresent();
    assertThat(store.findProfileIdByAccount(other)).contains(otherProfile);
  }

  @Test
  void theWritesWorkNormallyForAnAccountThatWasNeverAnonymized() {
    store.registerProfile(account, profile);

    assertThat(store.setProfileDiscoverable(profile, true)).isTrue();
    assertThat(store.saveProfileEmbedding(profile, true, embedding())).isTrue();
    assertThat(hasVector()).isTrue();
    assertThat(discoverable()).isTrue();
  }
}
