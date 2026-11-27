package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.Anonymization;
import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La anonimización contra las tablas reales (Postgres 17, BR-DATA-05): qué se borra, qué se
 * conserva, que repetirla no hace nada, que no toca a nadie más y que la base impide que un perfil
 * anonimizado vuelva a ser descubrible o a tener contacto.
 */
class JdbcAnonymizationRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
  private static final Instant LATER = T0.plusSeconds(3600);
  private static final String SECRET_NAME = "Ana Lopez Secretisima";
  private static final String PHOTO_KEY = "photo/" + UUID.randomUUID();

  private final JdbcClient jdbc = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcProfessionalProfileRepository professionals =
      new JdbcProfessionalProfileRepository(jdbc);
  private final JdbcCandidateProfileRepository candidates =
      new JdbcCandidateProfileRepository(jdbc);
  private final JdbcCandidateCollectionsRepository sets =
      new JdbcCandidateCollectionsRepository(jdbc);
  private final JdbcExperienceRepository experiences = new JdbcExperienceRepository(jdbc);
  private final JdbcAvailabilityExclusionRepository exclusions =
      new JdbcAvailabilityExclusionRepository(jdbc);
  private final JdbcAnonymizationRepository anonymization = new JdbcAnonymizationRepository(jdbc);

  /** Una cuenta con todo lo que `cv` guarda de una persona. */
  private record Person(
      AccountId account, ProfessionalProfileId professional, CandidateProfileId candidate) {}

  private AccountId account() {
    AccountId id = new AccountId(UUID.randomUUID());
    jdbc.sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", id.value())
        .update();
    return id;
  }

  private Person aFullPerson() {
    AccountId account = account();
    var professional =
        new ProfessionalProfile(
            new ProfessionalProfileId(UUID.randomUUID()),
            account,
            ProfessionalIdentity.of(SECRET_NAME, "Backend", "Mi biografia"),
            3,
            T0);
    professionals.insertIfAbsent(professional);
    jdbc.sql(
            "update professional_profiles set profile_photo_key = :key, profile_photo_type = 'PNG'"
                + " where id = :id")
        .param("key", PHOTO_KEY)
        .param("id", professional.id().value())
        .update();
    experiences.insert(
        professional.id(),
        Experience.of(
            new ProfileItemId(UUID.randomUUID()),
            "Empresa",
            "Dev",
            LocalDate.of(2020, 1, 1),
            null,
            null));
    var details =
        CandidateDetails.of(
            "ana@example.test",
            "+502 5555-0000",
            "GT",
            WorkModality.REMOTE,
            null,
            true,
            new BigDecimal("3000"),
            null,
            "USD",
            SalaryPeriod.MONTH);
    var candidate =
        CandidateProfile.create(new CandidateProfileId(UUID.randomUUID()), account, details, T0);
    candidates.insertIfAbsent(candidate);
    candidates.updateAvailabilityIfVersion(
        candidate.withAvailability(AvailabilityStatus.OPEN_TO_WORK, T0), 1);
    sets.replaceSkills(candidate.id(), List.of(new CandidateSkill("Java", 4)));
    sets.replaceLanguages(
        candidate.id(), List.of(new CandidateLanguage("en", LanguageProficiency.B2)));
    sets.replaceWorkAuthorizations(candidate.id(), List.of("GT"));
    exclusions.insert(candidate.id(), new AvailabilityExclusion(account(), T0));
    return new Person(account, professional.id(), candidate.id());
  }

  private int count(String table, String column, UUID id) {
    // Cada consulta lleva su tabla y su columna fijas en la prueba, nunca datos externos.
    String sql = "select count(*) from " + table + " where " + column + " = :id";
    return jdbc.sql(sql).param("id", id).query(Integer.class).single();
  }

  @Test
  void theProfessionalProfileKeepsOnlyAGenericNameAndLosesItsChildren() {
    var person = aFullPerson();

    var result =
        anonymization.anonymizeProfessional(
            person.account(), Anonymization.PLACEHOLDER_NAME, LATER);

    assertThat(result).isPresent();
    assertThat(result.get().version()).isEqualTo(4);
    var row =
        jdbc.sql(
                "select full_name, headline, bio, profile_photo_key, anonymized_at from professional_profiles where id = :id")
            .param("id", person.professional().value())
            .query(
                (rows, n) ->
                    List.of(
                        rows.getString(1),
                        String.valueOf(rows.getString(2)),
                        String.valueOf(rows.getString(3)),
                        String.valueOf(rows.getString(4)),
                        String.valueOf(rows.getObject(5) != null)))
            .single();
    assertThat(row).containsExactly("Candidato eliminado", "null", "null", "null", "true");
    assertThat(count("experiences", "professional_profile_id", person.professional().value()))
        .isZero();
  }

  @Test
  void theCandidateProfileLosesItsContactSalaryAvailabilitySetsAndListButKeepsCoarsePreferences() {
    var person = aFullPerson();
    var excludedAccount = exclusions.list(person.candidate()).get(0).excludedAccount();

    var result = anonymization.anonymizeCandidate(person.account(), LATER);

    assertThat(result).isPresent();
    var row =
        jdbc.sql(
                "select contact_email, contact_phone, discoverable, country_of_residence, desired_modality, salary_expectation_min, salary_currency, salary_period, availability_status from candidate_profiles where id = :id")
            .param("id", person.candidate().value())
            .query(
                (rows, n) ->
                    List.of(
                        String.valueOf(rows.getString(1)),
                        String.valueOf(rows.getString(2)),
                        String.valueOf(rows.getBoolean(3)),
                        rows.getString(4),
                        rows.getString(5),
                        String.valueOf(rows.getBigDecimal(6)),
                        String.valueOf(rows.getString(7)),
                        String.valueOf(rows.getString(8)),
                        rows.getString(9)))
            .single();
    assertThat(row)
        .containsExactly("null", "null", "false", "GT", "REMOTE", "null", "null", "null", "UNSET");
    assertThat(count("candidate_skills", "candidate_profile_id", person.candidate().value()))
        .isZero();
    assertThat(count("candidate_languages", "candidate_profile_id", person.candidate().value()))
        .isZero();
    assertThat(
            count(
                "candidate_work_authorizations",
                "candidate_profile_id",
                person.candidate().value()))
        .isZero();
    assertThat(
            count(
                "candidate_availability_exclusions",
                "candidate_profile_id",
                person.candidate().value()))
        .isZero();
    assertThat(excludedAccount).isNotNull();
  }

  @Test
  void anAnonymizedProfileDisappearsForTheApplicationAndCannotBeWrittenAgain() {
    var person = aFullPerson();
    anonymization.anonymizeProfessional(person.account(), Anonymization.PLACEHOLDER_NAME, LATER);
    anonymization.anonymizeCandidate(person.account(), LATER);

    assertThat(professionals.findByAccountId(person.account())).isEmpty();
    assertThat(candidates.findByAccountId(person.account())).isEmpty();
    var stale =
        CandidateProfile.create(
            person.candidate(),
            person.account(),
            CandidateDetails.of("x@y.co", null, null, null, null, null, null, null, null, null),
            LATER);
    assertThat(candidates.updateIfVersion(stale, 5)).isFalse();
    assertThat(candidates.updateDiscoverabilityIfVersion(stale, 5)).isFalse();
  }

  @Test
  void runningItTwiceDoesNothingTheSecondTime() {
    var person = aFullPerson();
    anonymization.anonymizeProfessional(person.account(), Anonymization.PLACEHOLDER_NAME, LATER);
    anonymization.anonymizeCandidate(person.account(), LATER);

    assertThat(
            anonymization.anonymizeProfessional(
                person.account(), Anonymization.PLACEHOLDER_NAME, LATER.plusSeconds(60)))
        .isEmpty();
    assertThat(anonymization.anonymizeCandidate(person.account(), LATER.plusSeconds(60))).isEmpty();

    assertThat(
            jdbc.sql("select version from professional_profiles where id = :id")
                .param("id", person.professional().value())
                .query(Long.class)
                .single())
        .isEqualTo(4);
  }

  @Test
  void anAccountWithoutProfilesHasNothingToAnonymize() {
    var account = account();

    assertThat(anonymization.anonymizeProfessional(account, Anonymization.PLACEHOLDER_NAME, LATER))
        .isEmpty();
    assertThat(anonymization.anonymizeCandidate(account, LATER)).isEmpty();
  }

  @Test
  void anotherPersonIsNeverTouchedAndRowsWhereTheAnonymizedIsTheExcludedAccountSurvive() {
    var gone = aFullPerson();
    var other = aFullPerson();
    exclusions.insert(other.candidate(), new AvailabilityExclusion(gone.account(), T0));

    anonymization.anonymizeProfessional(gone.account(), Anonymization.PLACEHOLDER_NAME, LATER);
    anonymization.anonymizeCandidate(gone.account(), LATER);

    assertThat(professionals.findByAccountId(other.account()).orElseThrow().identity().fullName())
        .isEqualTo(SECRET_NAME);
    assertThat(candidates.findByAccountId(other.account())).isPresent();
    assertThat(sets.findByProfileId(other.candidate()).skills()).hasSize(1);
    assertThat(exclusions.isExcluded(other.candidate(), gone.account())).isTrue();
  }

  @Test
  void theDatabaseRefusesToLeaveAnAnonymizedProfileDiscoverableOrWithContact() {
    var person = aFullPerson();
    anonymization.anonymizeCandidate(person.account(), LATER);
    var reveal =
        jdbc.sql("update candidate_profiles set discoverable = true where id = :id")
            .param("id", person.candidate().value());
    var restoreContact =
        jdbc.sql("update candidate_profiles set contact_email = 'ana@example.test' where id = :id")
            .param("id", person.candidate().value());

    assertThatThrownBy(reveal::update).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(restoreContact::update).isInstanceOf(DataAccessException.class);
  }

  @Test
  void theDatabaseRefusesToLeaveAnAnonymizedProfileWithSalaryOrAvailability() {
    var person = aFullPerson();
    anonymization.anonymizeCandidate(person.account(), LATER);
    var salary =
        jdbc.sql(
                "update candidate_profiles set salary_expectation_min = 100, salary_currency = 'USD', salary_period = 'MONTH' where id = :id")
            .param("id", person.candidate().value());
    var availability =
        jdbc.sql(
                "update candidate_profiles set availability_status = 'OPEN_TO_WORK' where id = :id")
            .param("id", person.candidate().value());

    assertThatThrownBy(salary::update).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(availability::update).isInstanceOf(DataAccessException.class);
  }

  @Test
  void aLiveCandidateStillCannotHaveNoEmail() {
    var person = aFullPerson();
    var blank =
        jdbc.sql("update candidate_profiles set contact_email = null where id = :id")
            .param("id", person.candidate().value());

    assertThatThrownBy(blank::update).isInstanceOf(DataAccessException.class);
  }

  // ADR-0021 §6: la foto se descarta antes de anonimizar y su clave vuelve para borrarla del
  // almacén.
  @Test
  void theProfilePhotoIsDiscardedOnceAndItsKeyReturned() {
    var person = aFullPerson();

    var first = anonymization.discardProfilePhoto(person.account());
    var again = anonymization.discardProfilePhoto(person.account());

    assertThat(first).contains(PHOTO_KEY);
    assertThat(again).isEmpty();
    assertThat(
            jdbc.sql(
                    "select count(*) from professional_profiles where id = :id"
                        + " and (profile_photo_key is not null or profile_photo_type is not null)")
                .param("id", person.professional().value())
                .query(Integer.class)
                .single())
        .isZero();
  }
}
