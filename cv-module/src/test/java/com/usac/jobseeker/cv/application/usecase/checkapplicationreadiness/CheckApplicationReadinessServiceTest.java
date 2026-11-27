package com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.usecase.uploadcv.CvFileStorePort;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CvFile;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** BR-CAND-01: nombre, contacto y un CV cargado; y con qué perfil y CV se postularía. */
class CheckApplicationReadinessServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

  private final AccountId me = new AccountId(UUID.randomUUID());
  private ProfessionalProfile professional;
  private CandidateProfile candidate;
  private final Files files = new Files();
  private final List<String> outcomes = new ArrayList<>();

  private final CheckApplicationReadinessService service =
      new CheckApplicationReadinessService(
          account -> Optional.ofNullable(professional).filter(p -> p.accountId().equals(account)),
          account -> Optional.ofNullable(candidate).filter(p -> p.accountId().equals(account)),
          files,
          (useCase, outcome) -> outcomes.add(useCase + ":" + outcome));

  private ApplicationReadiness check(AccountId account) {
    return service.execute(new CheckApplicationReadinessQuery(account));
  }

  @Test
  void anAccountWithNameContactAndCvIsReadyAndSaysWithWhat() {
    aProfessionalProfile();
    aCandidateProfile();
    CvFile old = files.add(candidate.id(), false);
    CvFile current = files.add(candidate.id(), true);

    ApplicationReadiness readiness = check(me);

    assertThat(readiness.ready()).isTrue();
    assertThat(readiness.missing()).isEmpty();
    assertThat(readiness.candidateProfileId()).contains(candidate.id().value());
    assertThat(readiness.currentCvFileId())
        .as("la versión vigente, no la anterior")
        .contains(current.id())
        .isNotEqualTo(Optional.of(old.id()));
    assertThat(outcomes).containsExactly("check_application_readiness:ready");
  }

  @Test
  void aNewAccountMissesEverything() {
    ApplicationReadiness readiness = check(me);

    assertThat(readiness.ready()).isFalse();
    assertThat(readiness.missing())
        .containsExactlyInAnyOrder(
            MissingRequirement.FULL_NAME, MissingRequirement.CONTACT, MissingRequirement.CV);
    assertThat(readiness.candidateProfileId()).isEmpty();
    assertThat(readiness.currentCvFileId()).isEmpty();
    assertThat(outcomes).containsExactly("check_application_readiness:missing");
  }

  @Test
  void withoutAnUploadedCvOnlyTheCvIsMissing() {
    aProfessionalProfile();
    aCandidateProfile();

    ApplicationReadiness readiness = check(me);

    assertThat(readiness.missing()).containsExactly(MissingRequirement.CV);
    assertThat(readiness.candidateProfileId()).contains(candidate.id().value());
  }

  @Test
  void withoutAProfessionalProfileTheNameIsMissing() {
    aCandidateProfile();
    files.add(candidate.id(), true);

    assertThat(check(me).missing()).containsExactly(MissingRequirement.FULL_NAME);
  }

  // Sin perfil de candidato no hay dónde colgar un CV: faltan los dos.
  @Test
  void withoutACandidateProfileTheContactAndTheCvAreMissing() {
    aProfessionalProfile();

    assertThat(check(me).missing())
        .containsExactlyInAnyOrder(MissingRequirement.CONTACT, MissingRequirement.CV);
  }

  @Test
  void anotherAccountsDataNeverCounts() {
    aProfessionalProfile();
    aCandidateProfile();
    files.add(candidate.id(), true);

    assertThat(check(new AccountId(UUID.randomUUID())).missing()).hasSize(3);
  }

  @Test
  void aReadyResultWithoutProfileOrCvCannotExist() {
    assertThatThrownBy(() -> new ApplicationReadiness(Set.of(), Optional.empty(), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theMissingSetCannotBeChangedByTheCaller() {
    ApplicationReadiness readiness = check(me);

    assertThatThrownBy(() -> readiness.missing().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private void aProfessionalProfile() {
    professional =
        ProfessionalProfile.create(
            new ProfessionalProfileId(UUID.randomUUID()),
            me,
            ProfessionalIdentity.of("Ana Pérez", null, null),
            NOW);
  }

  private void aCandidateProfile() {
    candidate =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            me,
            CandidateDetails.of("a@b.co", null, null, null, null, null, null, null, null, null),
            NOW);
  }

  /** Solo lo que lee el caso de uso; lo demás no debe llamarse. */
  private static final class Files implements CvFileStorePort {
    private final List<CvFile> rows = new ArrayList<>();

    CvFile add(CandidateProfileId profile, boolean current) {
      CvFile file =
          new CvFile(
              UUID.randomUUID(),
              profile,
              "cv/" + UUID.randomUUID(),
              "cv.pdf",
              FileType.PDF,
              10,
              "a".repeat(64),
              current,
              NOW);
      rows.add(file);
      return file;
    }

    @Override
    public Optional<CvFile> current(CandidateProfileId profile) {
      return rows.stream().filter(f -> f.profileId().equals(profile) && f.current()).findFirst();
    }

    @Override
    public List<CvFile> versions(CandidateProfileId profile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CvFile> find(CandidateProfileId profile, UUID fileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void lockProfile(CandidateProfileId profile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int count(CandidateProfileId profile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertCurrent(CvFile file) {
      throw new UnsupportedOperationException();
    }
  }
}
