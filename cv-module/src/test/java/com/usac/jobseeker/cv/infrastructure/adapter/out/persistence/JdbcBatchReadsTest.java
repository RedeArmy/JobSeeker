package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * TD-039: la lectura por lotes devuelve **exactamente** lo mismo que leer de a uno (la individual
 * es el caso de una), no mezcla los elementos de un perfil con los de otro, y cuesta un número fijo
 * de sentencias aunque se pidan muchos perfiles.
 */
class JdbcBatchReadsTest {

  private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  private final AtomicInteger statements = new AtomicInteger();
  private final JdbcClient plain = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcClient counted = JdbcClient.create(counting(CvPostgres.dataSource()));

  private DataSource counting(DataSource real) {
    return new DelegatingDataSource(real) {
      @Override
      public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("prepareStatement")
                      || method.getName().equals("createStatement")) {
                    statements.incrementAndGet();
                  }
                  try {
                    return method.invoke(connection, args);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }

  /** Una cuenta con perfil profesional y de candidato, y elementos distintos por perfil. */
  private record Person(
      AccountId account, ProfessionalProfileId professional, CandidateProfileId candidate) {}

  private Person person(int index) {
    AccountId account = new AccountId(UUID.randomUUID());
    plain
        .sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
    var professional =
        ProfessionalProfile.create(
            new ProfessionalProfileId(UUID.randomUUID()),
            account,
            ProfessionalIdentity.of("Persona " + index, "Titular " + index, null),
            T0);
    new JdbcProfessionalProfileRepository(plain).insertIfAbsent(professional);
    var candidate =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            account,
            CandidateDetails.of(
                "p" + index + "@b.co", null, null, null, null, null, null, null, null, null),
            T0);
    new JdbcCandidateProfileRepository(plain).insertIfAbsent(candidate);
    // Cada persona tiene una cantidad distinta de elementos, para que una mezcla se note.
    var experiences = new JdbcExperienceRepository(plain);
    for (int i = 0; i <= index % 3; i++) {
      experiences.insert(
          professional.id(),
          new Experience(
              new ProfileItemId(UUID.randomUUID()),
              "Empresa " + index,
              "Puesto " + index + "-" + i,
              LocalDate.of(2018 + i, 1, 1),
              null,
              "Descripción " + index));
    }
    if (index % 2 == 0) {
      new JdbcEducationRepository(plain)
          .insert(
              professional.id(),
              new Education(
                  new ProfileItemId(UUID.randomUUID()),
                  "Institución " + index,
                  "Grado " + index,
                  EducationLevel.BACHELOR,
                  LocalDate.of(2015, 1, 1)));
    }
    var sets = new JdbcCandidateCollectionsRepository(plain);
    sets.replaceSkills(
        candidate.id(), List.of(new CandidateSkill("Skill-" + index, 1 + index % 4)));
    if (index % 3 == 0) {
      sets.replaceLanguages(
          candidate.id(), List.of(new CandidateLanguage("es", LanguageProficiency.NATIVE)));
    }
    return new Person(account, professional.id(), candidate.id());
  }

  private List<Person> people(int count) {
    List<Person> people = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      people.add(person(i));
    }
    return people;
  }

  @Test
  void theBatchOfProfessionalCollectionsEqualsReadingOneByOneAndNeverMixesProfiles() {
    var people = people(7);
    var reader = new JdbcProfileCollectionsRepository(plain);

    var batch = reader.findAllByProfileIds(people.stream().map(Person::professional).toList());

    assertThat(batch).hasSize(7);
    for (Person person : people) {
      ProfileCollections single = reader.findByProfileId(person.professional());
      assertThat(batch.get(person.professional())).isEqualTo(single);
    }
    // Una persona con un solo puesto y otra con tres: nada se cuela de una a otra.
    assertThat(batch.get(people.get(0).professional()).experiences()).hasSize(1);
    assertThat(batch.get(people.get(2).professional()).experiences()).hasSize(3);
  }

  @Test
  void theBatchOfCandidateCollectionsEqualsReadingOneByOne() {
    var people = people(7);
    var reader = new JdbcCandidateCollectionsRepository(plain);

    var batch = reader.findAllByProfileIds(people.stream().map(Person::candidate).toList());

    for (Person person : people) {
      CandidateCollections single = reader.findByProfileId(person.candidate());
      assertThat(batch.get(person.candidate())).isEqualTo(single);
    }
  }

  @Test
  void aProfileWithNoItemsAndAnUnknownProfileHaveEmptyCollectionsNotAbsence() {
    var reader = new JdbcProfileCollectionsRepository(plain);
    var unknown = new ProfessionalProfileId(UUID.randomUUID());

    var batch = reader.findAllByProfileIds(List.of(unknown));

    assertThat(batch.get(unknown).experiences()).isEmpty();
    assertThat(batch.get(unknown).education()).isEmpty();
    assertThat(batch).isEqualTo(java.util.Map.of(unknown, reader.findByProfileId(unknown)));
  }

  @Test
  void theBatchOfProfilesByAccountEqualsTheSingleReadsAndSkipsUnknownAccounts() {
    var people = people(5);
    var professionals = new JdbcProfessionalProfileRepository(plain);
    var candidates = new JdbcCandidateProfileRepository(plain);
    var stranger = new AccountId(UUID.randomUUID());
    var accounts = new ArrayList<>(people.stream().map(Person::account).toList());
    accounts.add(stranger);

    var professionalBatch = professionals.findAllByAccountIds(accounts);
    var candidateBatch = candidates.findAllByAccountIds(accounts);

    assertThat(professionalBatch).hasSize(5).doesNotContainKey(stranger);
    assertThat(candidateBatch).hasSize(5).doesNotContainKey(stranger);
    for (Person person : people) {
      assertThat(professionalBatch.get(person.account()))
          .isEqualTo(professionals.findByAccountId(person.account()).orElseThrow());
      assertThat(candidateBatch.get(person.account()))
          .isEqualTo(candidates.findByAccountId(person.account()).orElseThrow());
    }
  }

  @Test
  void anAnonymizedProfileIsNotInTheBatch() {
    var gone = person(1);
    var anonymization = new JdbcAnonymizationRepository(plain);
    anonymization.anonymizeProfessional(gone.account(), "Anónimo", T0);
    anonymization.anonymizeCandidate(gone.account(), T0);

    assertThat(
            new JdbcCandidateProfileRepository(plain).findAllByAccountIds(List.of(gone.account())))
        .isEmpty();
    assertThat(
            new JdbcProfessionalProfileRepository(plain)
                .findAllByAccountIds(List.of(gone.account())))
        .isEmpty();
  }

  @Test
  void anEmptyRequestReadsNothingAndSendsNoStatement() {
    statements.set(0);

    assertThat(new JdbcProfessionalProfileRepository(counted).findAllByAccountIds(List.of()))
        .isEmpty();
    assertThat(new JdbcCandidateProfileRepository(counted).findAllByAccountIds(List.of()))
        .isEmpty();
    assertThat(
            new JdbcAvailabilityExclusionRepository(counted)
                .excludedAmong(List.of(), new AccountId(UUID.randomUUID())))
        .isEmpty();

    assertThat(statements.get()).isZero();
  }

  // La razón de TD-039: pedir 40 perfiles no cuesta 40 veces más sentencias que pedir 1.
  @Test
  void theNumberOfStatementsDoesNotGrowWithTheNumberOfProfiles() {
    var people = people(40);
    var professionals = new JdbcProfessionalProfileRepository(counted);
    var candidates = new JdbcCandidateProfileRepository(counted);
    var professionalItems = new JdbcProfileCollectionsRepository(counted);
    var candidateItems = new JdbcCandidateCollectionsRepository(counted);
    statements.set(0);

    professionals.findAllByAccountIds(people.stream().map(Person::account).toList());
    candidates.findAllByAccountIds(people.stream().map(Person::account).toList());
    professionalItems.findAllByProfileIds(people.stream().map(Person::professional).toList());
    candidateItems.findAllByProfileIds(people.stream().map(Person::candidate).toList());

    // 1 + 1 + 4 + 3 sentencias, no 40 veces más.
    assertThat(statements.get()).isEqualTo(9);
  }

  @Test
  void theExclusionBatchReturnsOnlyTheCandidatesWhoExcludedThatAccount() {
    var viewer = new AccountId(UUID.randomUUID());
    plain
        .sql("insert into accounts (id, is_recruiter_enabled) values (:id, true)")
        .param("id", viewer.value())
        .update();
    var excluding = person(1);
    var plainPerson = person(2);
    var other = person(3);
    var exclusions = new JdbcAvailabilityExclusionRepository(plain);
    exclusions.insert(excluding.candidate(), new AvailabilityExclusion(viewer, T0));
    exclusions.insert(other.candidate(), new AvailabilityExclusion(other.account(), T0));

    var found =
        exclusions.excludedAmong(
            List.of(excluding.candidate(), plainPerson.candidate(), other.candidate()), viewer);

    assertThat(found).containsExactly(excluding.candidate());
    assertThat(exclusions.isExcluded(excluding.candidate(), viewer)).isTrue();
    assertThat(exclusions.isExcluded(plainPerson.candidate(), viewer)).isFalse();
  }
}
