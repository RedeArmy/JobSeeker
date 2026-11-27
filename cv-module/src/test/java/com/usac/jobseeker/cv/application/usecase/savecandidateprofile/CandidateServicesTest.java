package com.usac.jobseeker.cv.application.usecase.savecandidateprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileQuery;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileService;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.CandidateLanguagesStorePort;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesCommand;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesService;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.CandidateSkillsStorePort;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsCommand;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsService;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsCommand;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsService;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.WorkAuthorizationsStorePort;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los cinco casos de uso del perfil de candidato, con dobles en memoria de sus puertos. */
class CandidateServicesTest {

  /** Un solo doble para los puertos de escritura y lectura, como lo es la base de datos. */
  private static final class Fake
      implements CandidateProfileStorePort,
          CandidateProfileReadPort,
          CandidateCollectionsReadPort,
          CandidateSkillsStorePort,
          CandidateLanguagesStorePort,
          WorkAuthorizationsStorePort {
    CandidateProfile profile;
    CandidateCollections sets = CandidateCollections.empty();

    @Override
    public Optional<CandidateProfile> findByAccountId(AccountId accountId) {
      return Optional.ofNullable(profile).filter(p -> p.accountId().equals(accountId));
    }

    @Override
    public boolean insertIfAbsent(CandidateProfile candidate) {
      if (profile != null) {
        return false;
      }
      profile = candidate;
      return true;
    }

    @Override
    public boolean updateIfVersion(CandidateProfile candidate, long expectedVersion) {
      if (profile == null || profile.version() != expectedVersion) {
        return false;
      }
      profile = candidate;
      return true;
    }

    @Override
    public CandidateCollections findByProfileId(CandidateProfileId profileId) {
      return sets;
    }

    @Override
    public void replaceSkills(CandidateProfileId profileId, List<CandidateSkill> items) {
      sets = new CandidateCollections(items, sets.languages(), sets.workAuthorizations());
    }

    @Override
    public void replaceLanguages(CandidateProfileId profileId, List<CandidateLanguage> items) {
      sets = new CandidateCollections(sets.skills(), items, sets.workAuthorizations());
    }

    @Override
    public void replaceWorkAuthorizations(CandidateProfileId profileId, List<String> items) {
      sets = new CandidateCollections(sets.skills(), sets.languages(), items);
    }
  }

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final Fake db = new Fake();
  private final List<OutboxEvent> events = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private boolean isCandidate = true;
  private int capabilityChecks;
  private boolean hasProfessional = true;
  private Instant now = Instant.parse("2026-11-23T10:00:00Z");

  private final SaveCandidateProfileService save =
      new SaveCandidateProfileService(
          db,
          db,
          a -> hasProfessional ? Optional.of(professional()) : Optional.empty(),
          a -> isCandidate,
          events::add,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          new Stamp(Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID));
  private final CandidateChange change =
      new CandidateChange(
          db,
          db,
          a -> {
            capabilityChecks++;
            return isCandidate;
          },
          events::add,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          new Stamp(Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID));
  private final GetMyCandidateProfileService get =
      new GetMyCandidateProfileService(
          db, db, a -> isCandidate, (useCase, outcome) -> metrics.add(useCase + ":" + outcome));
  private final ReplaceCandidateSkillsService skills =
      new ReplaceCandidateSkillsService(db, change);
  private final ReplaceCandidateLanguagesService languages =
      new ReplaceCandidateLanguagesService(db, change);
  private final ReplaceWorkAuthorizationsService authorizations =
      new ReplaceWorkAuthorizationsService(db, change);

  private com.usac.jobseeker.cv.domain.model.ProfessionalProfile professional() {
    return new com.usac.jobseeker.cv.domain.model.ProfessionalProfile(
        new com.usac.jobseeker.cv.domain.model.ProfessionalProfileId(UUID.randomUUID()),
        account,
        com.usac.jobseeker.cv.domain.model.ProfessionalIdentity.of("Ana", null, null),
        1,
        now);
  }

  private SaveCandidateProfileCommand saveCommand(String email, long version) {
    return new SaveCandidateProfileCommand(
        account, email, null, "gt", null, null, null, null, null, null, null, version);
  }

  private CandidateProfile created() {
    save.execute(saveCommand("ana@example.test", 0));
    events.clear();
    metrics.clear();
    return db.profile;
  }

  // ---- guardar ----

  @Test
  void theFirstSaveCreatesTheProfileAtVersionOneAndEmitsOneEventWithOnlyIdentifiers() {
    var result = save.execute(saveCommand("ana@example.test", 0));

    assertThat(result.created()).isTrue();
    assertThat(result.profile().version()).isEqualTo(1);
    assertThat(result.profile().details().countryOfResidence()).isEqualTo("GT");
    assertThat(events).hasSize(1);
    assertThat(events.get(0).aggregateType()).isEqualTo("candidate_profile");
    assertThat(events.get(0).payload()).doesNotContain("ana@example.test");
    assertThat(metrics).containsExactly("save_candidate_profile:created");
  }

  @Test
  void anUpdateAdvancesTheVersionAndTheSameDataAgainChangesNothing() {
    created();

    var edited = save.execute(saveCommand("otro@example.test", 1));
    var same = save.execute(saveCommand("otro@example.test", 2));

    assertThat(edited.created()).isFalse();
    assertThat(edited.profile().version()).isEqualTo(2);
    assertThat(same.profile().version()).isEqualTo(2);
    assertThat(events).hasSize(1);
    assertThat(metrics)
        .containsExactly("save_candidate_profile:updated", "save_candidate_profile:unchanged");
  }

  @Test
  void aStaleVersionOrAFirstSaveWhenOneExistsIsRejectedAndNothingIsWritten() {
    created();
    var stale = saveCommand("x@example.test", 9);
    var asNew = saveCommand("x@example.test", 0);

    assertThatThrownBy(() -> save.execute(stale)).isInstanceOf(StaleStateException.class);
    assertThatThrownBy(() -> save.execute(asNew)).isInstanceOf(StaleStateException.class);

    assertThat(db.profile.version()).isEqualTo(1);
    assertThat(events).isEmpty();
  }

  @Test
  void creatingWithAVersionOtherThanZeroWhenNoneExistsIsStale() {
    var command = saveCommand("a@example.test", 3);

    assertThatThrownBy(() -> save.execute(command)).isInstanceOf(StaleStateException.class);
  }

  @Test
  void withoutTheCandidateCapabilityNothingIsReadOrWrittenAndTheDataIsNotEvenValidated() {
    isCandidate = false;
    var command = saveCommand("mal", 0);
    var query = new GetMyCandidateProfileQuery(account);

    assertThatThrownBy(() -> save.execute(command))
        .isInstanceOf(ForbiddenCapabilityException.class);
    assertThatThrownBy(() -> get.execute(query)).isInstanceOf(ForbiddenCapabilityException.class);

    assertThat(db.profile).isNull();
    assertThat(metrics)
        .containsExactly("save_candidate_profile:forbidden", "get_my_candidate_profile:forbidden");
  }

  @Test
  void theCandidateProfileNeedsTheProfessionalOneFirst() {
    hasProfessional = false;
    var command = saveCommand("a@example.test", 0);

    assertThatThrownBy(() -> save.execute(command))
        .isInstanceOf(ValidationFailedException.class)
        .extracting(e -> ((ValidationFailedException) e).violations().get(0).field())
        .isEqualTo("professionalProfile");
    assertThat(db.profile).isNull();
  }

  @Test
  void invalidDataIsCountedAndRejected() {
    var command = saveCommand("mal", 0);

    assertThatThrownBy(() -> save.execute(command)).isInstanceOf(ValidationFailedException.class);

    assertThat(metrics).containsExactly("save_candidate_profile:invalid");
  }

  @Test
  void aNegativeVersionIsRejectedByTheCommand() {
    assertThatThrownBy(() -> saveCommand("a@example.test", -1))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> new ReplaceCandidateSkillsCommand(account, -1, List.of()))
        .isInstanceOf(ValidationFailedException.class);
  }

  // ---- leer ----

  @Test
  void readingBeforeCreatingIsEmptyAndAfterwardsReturnsTheProfileWithItsSets() {
    assertThat(get.execute(new GetMyCandidateProfileQuery(account))).isEmpty();
    created();
    db.sets =
        new CandidateCollections(List.of(new CandidateSkill("Java", 3)), List.of(), List.of("GT"));

    var view = get.execute(new GetMyCandidateProfileQuery(account)).orElseThrow();

    assertThat(view.version()).isEqualTo(1);
    assertThat(view.collections().skills()).containsExactly(new CandidateSkill("Java", 3));
    assertThat(metrics).containsExactly("get_my_candidate_profile:found");
  }

  // ---- reemplazar conjuntos ----

  @Test
  void replacingASetWritesItAdvancesTheVersionAndEmitsItsOwnChangeKind() {
    created();

    var view =
        skills.execute(
            new ReplaceCandidateSkillsCommand(
                account, 1, List.of(new CandidateSkill(" Java ", 4))));
    languages.execute(
        new ReplaceCandidateLanguagesCommand(
            account, 2, List.of(new CandidateLanguage("EN", LanguageProficiency.B2))));
    var last =
        authorizations.execute(new ReplaceWorkAuthorizationsCommand(account, 3, List.of("gt")));

    assertThat(view.version()).isEqualTo(2);
    assertThat(last.version()).isEqualTo(4);
    assertThat(last.collections().skills()).containsExactly(new CandidateSkill("Java", 4));
    assertThat(last.collections().languages())
        .containsExactly(new CandidateLanguage("en", LanguageProficiency.B2));
    assertThat(last.collections().workAuthorizations()).containsExactly("GT");
    assertThat(events)
        .extracting(e -> e.payload())
        .anyMatch(p -> p.contains(ProfileUpdated.CHANGE_SKILLS))
        .anyMatch(p -> p.contains(ProfileUpdated.CHANGE_LANGUAGES))
        .anyMatch(p -> p.contains(ProfileUpdated.CHANGE_WORK_AUTHORIZATIONS));
  }

  // TD-003: antes cada reemplazo preguntaba dos veces la capacidad, una consulta idéntica de más.
  @Test
  void replacingAnyOfTheThreeSetsChecksTheCapabilityExactlyOnce() {
    created();
    capabilityChecks = 0;

    skills.execute(
        new ReplaceCandidateSkillsCommand(account, 1, List.of(new CandidateSkill("Java", 4))));
    int forSkills = capabilityChecks;
    capabilityChecks = 0;
    languages.execute(
        new ReplaceCandidateLanguagesCommand(
            account, 2, List.of(new CandidateLanguage("en", LanguageProficiency.B2))));
    int forLanguages = capabilityChecks;
    capabilityChecks = 0;
    authorizations.execute(new ReplaceWorkAuthorizationsCommand(account, 3, List.of("GT")));
    int forAuthorizations = capabilityChecks;

    assertThat(List.of(forSkills, forLanguages, forAuthorizations)).containsOnly(1);
  }

  @Test
  void replacingWithTheSameContentChangesNothing() {
    created();
    var command =
        new ReplaceCandidateSkillsCommand(account, 1, List.of(new CandidateSkill("Java", null)));
    skills.execute(command);
    events.clear();

    var again =
        skills.execute(
            new ReplaceCandidateSkillsCommand(
                account, 2, List.of(new CandidateSkill("Java", null))));

    assertThat(again.version()).isEqualTo(2);
    assertThat(events).isEmpty();
    assertThat(metrics).contains("replace_candidate_skills:unchanged");
  }

  @Test
  void replacingFailsWithoutCapabilityWithoutProfileOrWithAStaleVersionAndWritesNothing() {
    var command = new ReplaceCandidateSkillsCommand(account, 0, List.of());
    assertThatThrownBy(() -> skills.execute(command)).isInstanceOf(NotFoundException.class);
    created();
    var stale =
        new ReplaceCandidateSkillsCommand(account, 5, List.of(new CandidateSkill("Java", null)));
    assertThatThrownBy(() -> skills.execute(stale)).isInstanceOf(StaleStateException.class);
    isCandidate = false;
    var current = new ReplaceCandidateSkillsCommand(account, 1, List.of());
    assertThatThrownBy(() -> skills.execute(current))
        .isInstanceOf(ForbiddenCapabilityException.class);

    assertThat(db.sets.skills()).isEmpty();
    assertThat(db.profile.version()).isEqualTo(1);
    assertThat(events).isEmpty();
  }

  @Test
  void anInvalidSetIsRejectedBeforeAnythingChanges() {
    created();
    var duplicated =
        new ReplaceCandidateSkillsCommand(
            account, 1, List.of(new CandidateSkill("A", null), new CandidateSkill("a", null)));

    assertThatThrownBy(() -> skills.execute(duplicated))
        .isInstanceOf(ValidationFailedException.class);

    assertThat(db.profile.version()).isEqualTo(1);
    assertThat(metrics).contains("replace_candidate_skills:invalid");
  }
}
