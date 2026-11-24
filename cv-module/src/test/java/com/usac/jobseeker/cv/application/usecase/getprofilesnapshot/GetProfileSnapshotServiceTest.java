package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.InMemoryProfessionalProfiles;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.domain.model.WorkSchedule;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El contrato de lectura de `cv` hacia otros módulos: qué datos salen y, sobre todo, cuáles **no**.
 * Un perfil lleno de datos reconocibles se proyecta y ninguno de los personales aparece en ningún
 * lugar del resultado; además, la forma misma del resultado no tiene un campo para ellos, de modo
 * que añadir uno obliga a cambiar esta prueba.
 */
class GetProfileSnapshotServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");

  // Datos personales reconocibles: ninguno debe aparecer en el resultado.
  private static final String FULL_NAME = "Ana Lopez Secretisima";
  private static final String BIO = "Mi biografia privada";
  private static final String COMPANY = "Empresa Confidencial SA";
  private static final String INSTITUTION = "Universidad Reservada";
  private static final String ISSUER = "Emisor Reservado";
  private static final String CREDENTIAL_URL = "https://credencial.example/privada";
  private static final String PROJECT_URL = "https://proyecto.example/privado";
  private static final String EMAIL = "ana.secreta@example.test";
  private static final String PHONE = "+502 5555-0000";
  private static final List<String> PERSONAL =
      List.of(
          FULL_NAME, BIO, COMPANY, INSTITUTION, ISSUER, CREDENTIAL_URL, PROJECT_URL, EMAIL, PHONE);

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final InMemoryProfessionalProfiles professionals = new InMemoryProfessionalProfiles();
  private final List<String> metrics = new ArrayList<>();
  private boolean isCandidate = true;
  private CandidateProfile candidate;
  private ProfileCollections items = ProfileCollections.empty();
  private CandidateCollections sets = CandidateCollections.empty();

  private final GetProfileSnapshotService service =
      new GetProfileSnapshotService(
          a -> isCandidate,
          professionals,
          profileId -> items,
          a -> Optional.ofNullable(candidate),
          profileId -> sets,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome));

  private void aFullProfile() {
    professionals.insertIfAbsent(
        new ProfessionalProfile(
            new ProfessionalProfileId(UUID.randomUUID()),
            account,
            ProfessionalIdentity.of(FULL_NAME, "Backend Developer", BIO),
            7,
            T0));
    candidate =
        new CandidateProfile(
            new CandidateProfileId(UUID.randomUUID()),
            account,
            CandidateDetails.of(
                EMAIL,
                PHONE,
                "gt",
                WorkModality.HYBRID,
                WorkSchedule.FULL_TIME,
                true,
                new BigDecimal("3000"),
                new BigDecimal("4500"),
                "usd",
                SalaryPeriod.MONTH),
            false,
            com.usac.jobseeker.cv.domain.model.AvailabilityStatus.OPEN_TO_WORK,
            3,
            T0);
    items =
        new ProfileCollections(
            List.of(
                Experience.of(
                    id(),
                    COMPANY,
                    "Backend Engineer",
                    LocalDate.of(2020, 1, 1),
                    null,
                    "Servicios Java")),
            List.of(
                Education.of(
                    id(),
                    INSTITUTION,
                    "Ingenieria en Sistemas",
                    EducationLevel.BACHELOR,
                    LocalDate.of(2019, 12, 1))),
            List.of(
                Certification.of(
                    id(), "AWS Developer", ISSUER, LocalDate.of(2022, 1, 1), null, CREDENTIAL_URL)),
            List.of(
                Project.of(
                    id(),
                    "Plataforma",
                    "Una plataforma",
                    ProjectType.PERSONAL,
                    "Lider",
                    LocalDate.of(2021, 1, 1),
                    null,
                    PROJECT_URL)));
    sets =
        new CandidateCollections(
            List.of(new CandidateSkill("Java", 4), new CandidateSkill("SQL", null)),
            List.of(new CandidateLanguage("en", LanguageProficiency.B2)),
            List.of("GT", "MX"));
  }

  private static ProfileItemId id() {
    return new ProfileItemId(UUID.randomUUID());
  }

  private ProfileSnapshot snapshot() {
    return service.execute(new GetProfileSnapshotQuery(account)).orElseThrow();
  }

  @Test
  void theStructuredDataAndTheEligibilityDataAreProjectedWithCatalogValuesAsNames() {
    aFullProfile();

    var s = snapshot();

    assertThat(s.accountId()).isEqualTo(account.value());
    assertThat(s.candidateProfileId()).isEqualTo(candidate.id().value());
    assertThat(s.professionalVersion()).isEqualTo(7);
    assertThat(s.candidateVersion()).isEqualTo(3);
    assertThat(s.discoverable()).isFalse();
    assertThat(s.headline()).isEqualTo("Backend Developer");
    assertThat(s.countryOfResidence()).isEqualTo("GT");
    assertThat(s.desiredModality()).isEqualTo("HYBRID");
    assertThat(s.preferredWorkSchedule()).isEqualTo("FULL_TIME");
    assertThat(s.relocationWillingness()).isTrue();
    assertThat(s.salary())
        .isEqualTo(
            new ProfileSnapshot.Salary(
                new BigDecimal("3000.00"), new BigDecimal("4500.00"), "USD", "MONTH"));
    assertThat(s.experiences())
        .containsExactly(
            new ProfileSnapshot.Experience(
                "Backend Engineer", LocalDate.of(2020, 1, 1), null, "Servicios Java"));
    assertThat(s.education())
        .containsExactly(
            new ProfileSnapshot.Education(
                "Ingenieria en Sistemas", "BACHELOR", LocalDate.of(2019, 12, 1)));
    assertThat(s.certifications())
        .containsExactly(
            new ProfileSnapshot.Certification("AWS Developer", LocalDate.of(2022, 1, 1), null));
    assertThat(s.projects())
        .containsExactly(
            new ProfileSnapshot.Project(
                "Plataforma",
                "Una plataforma",
                "PERSONAL",
                "Lider",
                LocalDate.of(2021, 1, 1),
                null));
    assertThat(s.skills())
        .containsExactly(
            new ProfileSnapshot.Skill("Java", 4), new ProfileSnapshot.Skill("SQL", null));
    assertThat(s.languages()).containsExactly(new ProfileSnapshot.Language("en", "B2"));
    assertThat(s.workAuthorizations()).containsExactly("GT", "MX");
    assertThat(metrics).containsExactly("get_profile_snapshot:found");
  }

  @Test
  void noPersonalDatumAppearsAnywhereInTheResult() {
    aFullProfile();

    String everything = snapshot().toString();

    for (String personal : PERSONAL) {
      assertThat(everything).as(personal).doesNotContain(personal);
    }
  }

  // El contrato con `matching`: la forma del resultado no tiene dónde guardar un dato personal.
  @Test
  void theShapeOfTheResultHasNoFieldForAnyPersonalDatum() {
    Set<String> forbidden =
        Set.of(
            "fullName",
            "name_",
            "bio",
            "company",
            "institution",
            "issuingOrganization",
            "issuer",
            "url",
            "credentialUrl",
            "email",
            "contactEmail",
            "phone",
            "contactPhone",
            "photo",
            "availability",
            "availabilityStatus");
    List<String> found = new ArrayList<>();
    for (Class<?> type :
        List.of(
            ProfileSnapshot.class,
            ProfileSnapshot.Experience.class,
            ProfileSnapshot.Education.class,
            ProfileSnapshot.Certification.class,
            ProfileSnapshot.Project.class,
            ProfileSnapshot.Skill.class,
            ProfileSnapshot.Language.class,
            ProfileSnapshot.Salary.class)) {
      for (RecordComponent component : type.getRecordComponents()) {
        if (forbidden.contains(component.getName())) {
          found.add(type.getSimpleName() + "." + component.getName());
        }
        // Ningún tipo interno de `cv` cruza el límite del módulo.
        assertThat(component.getType().getPackageName())
            .as(type.getSimpleName() + "." + component.getName())
            .doesNotContain("cv.domain");
      }
    }

    assertThat(found).isEmpty();
  }

  @Test
  void theAvailabilityStatusIsNotPartOfTheSnapshotBecauseWhoMaySeeItDependsOnWhoIsAsking() {
    aFullProfile();

    assertThat(snapshot().toString()).doesNotContain("OPEN_TO_WORK", "availability");
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityHasNoSnapshotEvenWithAProfile() {
    aFullProfile();
    isCandidate = false;

    assertThat(service.execute(new GetProfileSnapshotQuery(account))).isEmpty();
    assertThat(metrics).containsExactly("get_profile_snapshot:unavailable");
  }

  @Test
  void withoutTheProfessionalOrTheCandidateProfileThereIsNothingToRead() {
    assertThat(service.execute(new GetProfileSnapshotQuery(account))).isEmpty();
    aFullProfile();
    candidate = null;
    assertThat(service.execute(new GetProfileSnapshotQuery(account))).isEmpty();

    assertThat(metrics).containsOnly("get_profile_snapshot:unavailable");
  }

  @Test
  void theResultIsAnImmutableCopy() {
    aFullProfile();
    var s = snapshot();
    var skills = s.skills();

    org.assertj.core.api.Assertions.assertThatThrownBy(skills::clear)
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
