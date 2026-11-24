package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.domain.model.WorkSchedule;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El perfil de candidato y sus tres conjuntos contra las tablas reales (Postgres 17): ida y vuelta
 * de cada campo, las dos escrituras condicionales, el reemplazo entero y lo que la base rechaza
 * aunque el dominio ya lo haya rechazado.
 */
class JdbcCandidateRepositoriesTest {

  private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcCandidateProfileRepository profiles = new JdbcCandidateProfileRepository(jdbc);
  private final JdbcCandidateCollectionsRepository sets =
      new JdbcCandidateCollectionsRepository(jdbc);

  private AccountId account() {
    AccountId account = new AccountId(UUID.randomUUID());
    jdbc.sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
    return account;
  }

  private static CandidateDetails full() {
    return CandidateDetails.of(
        "ana@example.test",
        "+502 5555-1234",
        "GT",
        WorkModality.HYBRID,
        WorkSchedule.PART_TIME,
        true,
        new BigDecimal("3000.50"),
        new BigDecimal("4500"),
        "USD",
        SalaryPeriod.MONTH);
  }

  private static CandidateDetails minimal() {
    return CandidateDetails.of(
        "min@example.test", null, null, null, null, null, null, null, null, null);
  }

  private CandidateProfile created(CandidateDetails details) {
    var profile =
        CandidateProfile.create(new CandidateProfileId(UUID.randomUUID()), account(), details, T0);
    assertThat(profiles.insertIfAbsent(profile)).isTrue();
    return profile;
  }

  @Test
  void everyFieldOfAFullProfileIsReadBack() {
    var saved = created(full());

    var read = profiles.findByAccountId(saved.accountId()).orElseThrow();

    assertThat(read.details()).isEqualTo(saved.details());
    assertThat(read.details().salary().min()).isEqualByComparingTo("3000.50");
    assertThat(read.discoverable()).isTrue();
    assertThat(read.version()).isEqualTo(1);
    assertThat(read.updatedAt()).isEqualTo(T0);
  }

  @Test
  void aMinimalProfileKeepsItsOptionalFieldsAsNull() {
    var saved = created(minimal());

    var read = profiles.findByAccountId(saved.accountId()).orElseThrow();

    assertThat(read.details().salary()).isNull();
    assertThat(read.details().countryOfResidence()).isNull();
    assertThat(read.details().desiredModality()).isNull();
    assertThat(read.details().relocationWillingness()).isFalse();
  }

  @Test
  void aSecondInsertForTheSameAccountWritesNothing() {
    var first = created(minimal());
    var second =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()), first.accountId(), full(), T0);

    assertThat(profiles.insertIfAbsent(second)).isFalse();

    assertThat(profiles.findByAccountId(first.accountId()).orElseThrow().id())
        .isEqualTo(first.id());
  }

  @Test
  void anUpdateOnlyWritesWithTheExpectedVersionAndNeverTouchesDiscoverabilityOrAvailability() {
    var saved = created(minimal());
    jdbc.sql(
            "update candidate_profiles set discoverable = false, availability_status = 'OPEN_TO_WORK' where id = :id")
        .param("id", saved.id().value())
        .update();

    var edited = saved.edit(full(), T0.plusSeconds(5));

    assertThat(profiles.updateIfVersion(edited, 7)).isFalse();
    assertThat(profiles.updateIfVersion(edited, 1)).isTrue();
    assertThat(profiles.updateIfVersion(edited, 1)).isFalse();
    var read = profiles.findByAccountId(saved.accountId()).orElseThrow();
    assertThat(read.version()).isEqualTo(2);
    assertThat(read.details()).isEqualTo(full());
    assertThat(read.discoverable()).isFalse();
    assertThat(read.availability().name()).isEqualTo("OPEN_TO_WORK");
  }

  @Test
  void anAbsentProfileIsEmptyAndHasNoSets() {
    assertThat(profiles.findByAccountId(new AccountId(UUID.randomUUID()))).isEmpty();
    assertThat(sets.findByProfileId(new CandidateProfileId(UUID.randomUUID())).skills()).isEmpty();
  }

  @Test
  void eachSetIsReplacedWholeAndReadInAStableOrderWithoutTouchingTheOthers() {
    var profile = created(minimal()).id();

    sets.replaceSkills(
        profile, List.of(new CandidateSkill("sql", null), new CandidateSkill("Java", 4)));
    sets.replaceLanguages(
        profile,
        List.of(
            new CandidateLanguage("es", LanguageProficiency.NATIVE),
            new CandidateLanguage("en", LanguageProficiency.B2)));
    sets.replaceWorkAuthorizations(profile, List.of("MX", "GT"));
    sets.replaceSkills(
        profile, List.of(new CandidateSkill("Rust", 1), new CandidateSkill("Go", null)));

    var read = sets.findByProfileId(profile);
    assertThat(read.skills())
        .containsExactly(new CandidateSkill("Go", null), new CandidateSkill("Rust", 1));
    assertThat(read.languages())
        .containsExactly(
            new CandidateLanguage("en", LanguageProficiency.B2),
            new CandidateLanguage("es", LanguageProficiency.NATIVE));
    assertThat(read.workAuthorizations()).containsExactly("GT", "MX");
    sets.replaceLanguages(profile, List.of());
    assertThat(sets.findByProfileId(profile).languages()).isEmpty();
    assertThat(sets.findByProfileId(profile).skills()).hasSize(2);
  }

  @Test
  void theSetsOfOneProfileAreNeverMixedWithAnotherOnes() {
    var mine = created(minimal()).id();
    var other = created(minimal()).id();
    sets.replaceWorkAuthorizations(mine, List.of("GT"));

    sets.replaceWorkAuthorizations(other, List.of());

    assertThat(sets.findByProfileId(mine).workAuthorizations()).containsExactly("GT");
  }

  @Test
  void theDatabaseRejectsWhatTheDomainWouldHaveRejectedToo() {
    var profile = created(minimal()).id();
    var tooHigh = List.of(new CandidateSkill("X", 9));
    var duplicated = List.of(new CandidateSkill("Java", null), new CandidateSkill(" JAVA ", null));
    var badCountry = List.of("gt");

    assertThatThrownBy(() -> sets.replaceSkills(profile, tooHigh))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> sets.replaceSkills(profile, duplicated))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> sets.replaceWorkAuthorizations(profile, badCountry))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aSalaryAmountWithoutItsCurrencyIsRejectedByTheDatabase() {
    var saved = created(minimal());

    var id = saved.id().value();
    var statement =
        jdbc.sql("update candidate_profiles set salary_expectation_min = 100 where id = :id")
            .param("id", id);

    assertThatThrownBy(statement::update).isInstanceOf(DataAccessException.class);
  }
}
