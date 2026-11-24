package com.usac.jobseeker.matching.infrastructure.adapter.out.cv;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.GetProfileSnapshotQuery;
import com.usac.jobseeker.cv.application.usecase.getprofilesnapshot.ProfileSnapshot;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La capa anticorrupción hacia `cv`: traduce sin perder nada de lo que `matching` usa, y el tipo de
 * destino no tiene dónde guardar un dato personal (contrato del TDD de matching §9).
 */
class CvCandidateSignalsReaderTest {

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final UUID profile = UUID.randomUUID();
  private final List<GetProfileSnapshotQuery> asked = new ArrayList<>();

  private ProfileSnapshot snapshot() {
    return new ProfileSnapshot(
        profile,
        account.value(),
        true,
        7,
        3,
        "Backend",
        "GT",
        "HYBRID",
        "FULL_TIME",
        true,
        new ProfileSnapshot.Salary(new BigDecimal("3000"), null, "USD", "MONTH"),
        List.of(new ProfileSnapshot.Experience("Dev", LocalDate.of(2020, 1, 1), null, "Pagos")),
        List.of(new ProfileSnapshot.Education("Ingeniería", "BACHELOR", LocalDate.of(2019, 12, 1))),
        List.of(new ProfileSnapshot.Certification("AWS", LocalDate.of(2022, 1, 1), null)),
        List.of(
            new ProfileSnapshot.Project(
                "Plataforma",
                "Una plataforma",
                "PERSONAL",
                "Líder",
                LocalDate.of(2021, 1, 1),
                null)),
        List.of(new ProfileSnapshot.Skill("Java", 4), new ProfileSnapshot.Skill("SQL", null)),
        List.of(new ProfileSnapshot.Language("en", "B2")),
        List.of("GT", "MX"));
  }

  private CvCandidateSignalsReader reader(Optional<ProfileSnapshot> answer) {
    return new CvCandidateSignalsReader(
        query -> {
          asked.add(query);
          return answer;
        });
  }

  @Test
  void everythingMatchingUsesIsTranslated() {
    var signals = reader(Optional.of(snapshot())).read(account).orElseThrow();

    assertThat(asked).containsExactly(new GetProfileSnapshotQuery(account));
    assertThat(signals.profileId().value()).isEqualTo(profile);
    assertThat(signals.accountId()).isEqualTo(account);
    assertThat(signals.discoverable()).isTrue();
    assertThat(signals.headline()).isEqualTo("Backend");
    assertThat(signals.countryOfResidence()).isEqualTo("GT");
    assertThat(signals.desiredModality()).isEqualTo("HYBRID");
    assertThat(signals.relocationWillingness()).isTrue();
    assertThat(signals.salary())
        .isEqualTo(new CandidateSignals.Salary(new BigDecimal("3000"), null, "USD", "MONTH"));
    assertThat(signals.experiences())
        .containsExactly(
            new CandidateSignals.Experience("Dev", LocalDate.of(2020, 1, 1), null, "Pagos"));
    assertThat(signals.education())
        .containsExactly(
            new CandidateSignals.Education("Ingeniería", "BACHELOR", LocalDate.of(2019, 12, 1)));
    assertThat(signals.certifications()).containsExactly("AWS");
    assertThat(signals.projects())
        .containsExactly(
            new CandidateSignals.Project("Plataforma", "Una plataforma", "PERSONAL", "Líder"));
    assertThat(signals.skills())
        .containsExactly(
            new CandidateSignals.Skill("Java", 4), new CandidateSignals.Skill("SQL", null));
    assertThat(signals.languages()).containsExactly(new CandidateSignals.Language("en", "B2"));
    assertThat(signals.workAuthorizations()).containsExactly("GT", "MX");
  }

  @Test
  void anAbsentSalaryStaysAbsent() {
    var snap = snapshot();
    var noSalary =
        new ProfileSnapshot(
            snap.candidateProfileId(),
            snap.accountId(),
            true,
            1,
            1,
            null,
            null,
            null,
            null,
            false,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    assertThat(reader(Optional.of(noSalary)).read(account).orElseThrow().salary()).isNull();
  }

  @Test
  void noReadableProfileIsNoSignals() {
    assertThat(reader(Optional.empty()).read(account)).isEmpty();
  }

  // El contrato con `cv` (TDD de matching §9): el destino no tiene dónde guardar identidad.
  @Test
  void theTargetShapeHasNoFieldForAnyPersonalDatum() {
    Set<String> forbidden =
        Set.of(
            "fullName",
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
            CandidateSignals.class,
            CandidateSignals.Experience.class,
            CandidateSignals.Education.class,
            CandidateSignals.Project.class,
            CandidateSignals.Skill.class,
            CandidateSignals.Language.class,
            CandidateSignals.Salary.class)) {
      for (RecordComponent component : type.getRecordComponents()) {
        if (forbidden.contains(component.getName())) {
          found.add(type.getSimpleName() + "." + component.getName());
        }
        assertThat(component.getType().getPackageName())
            .as(component.getName())
            .doesNotContain("com.usac.jobseeker.cv");
      }
    }

    assertThat(found).isEmpty();
  }
}
