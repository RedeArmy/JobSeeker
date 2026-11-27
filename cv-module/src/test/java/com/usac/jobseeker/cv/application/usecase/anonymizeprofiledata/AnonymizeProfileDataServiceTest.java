package com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.Stamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Una cuenta, un evento: lo que se anonimiza decide si hay evento y a qué agregado se asocia. */
class AnonymizeProfileDataServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final List<OutboxEvent> events = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final List<String> calls = new ArrayList<>();
  private Optional<AnonymizationStorePort.Anonymized> professional = Optional.empty();
  private Optional<AnonymizationStorePort.Anonymized> candidate = Optional.empty();
  private List<String> cvKeys = List.of();
  private Optional<String> photoKey = Optional.empty();

  private final AnonymizeProfileDataService service =
      new AnonymizeProfileDataService(
          new AnonymizationStorePort() {
            public Optional<Anonymized> anonymizeProfessional(
                AccountId id, String placeholder, Instant now) {
              calls.add("professional:" + placeholder + ":" + now);
              return professional;
            }

            public Optional<Anonymized> anonymizeCandidate(AccountId id, Instant now) {
              calls.add("candidate:" + now);
              return candidate;
            }

            public List<String> discardCvFiles(UUID candidateProfileId, Instant now) {
              calls.add("cv:" + now);
              return cvKeys;
            }

            public Optional<String> discardProfilePhoto(AccountId id) {
              calls.add("photo");
              return photoKey;
            }
          },
          events::add,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          new Stamp(Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID));

  private AnonymizationStorePort.Anonymized anonymized(long version) {
    return new AnonymizationStorePort.Anonymized(UUID.randomUUID(), version);
  }

  @Test
  void anAccountWithBothProfilesEmitsOneEventAssociatedToTheProfessionalOne() {
    professional = Optional.of(anonymized(5));
    candidate = Optional.of(anonymized(9));

    var result = service.execute(new AnonymizeProfileDataCommand(account));

    assertThat(result).isEqualTo(AnonymizeProfileDataUseCase.Result.ANONYMIZED);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).eventType()).isEqualTo("profile.anonymized");
    assertThat(events.get(0).aggregateType()).isEqualTo("professional_profile");
    assertThat(events.get(0).aggregateId()).isEqualTo(professional.get().profileId());
    assertThat(events.get(0).aggregateVersion()).isEqualTo(5);
    assertThat(events.get(0).payload())
        .isEqualTo("{\"accountId\":\"" + account + "\",\"version\":5}");
    assertThat(calls)
        .containsExactly(
            "photo", "professional:Candidato eliminado:" + NOW, "candidate:" + NOW, "cv:" + NOW);
    assertThat(metrics).containsExactly("anonymize_profile_data:anonymized");
  }

  @Test
  void aProfessionalProfileAloneAndACandidateProfileAloneEachEmitTheirEvent() {
    professional = Optional.of(anonymized(2));
    service.execute(new AnonymizeProfileDataCommand(account));
    professional = Optional.empty();
    candidate = Optional.of(anonymized(4));
    service.execute(new AnonymizeProfileDataCommand(account));

    assertThat(events)
        .extracting(OutboxEvent::aggregateType)
        .containsExactly("professional_profile", "candidate_profile");
  }

  @Test
  void anAccountThatHasNothingLeftEmitsNothingSoARetryIsHarmless() {
    var result = service.execute(new AnonymizeProfileDataCommand(account));

    assertThat(result).isEqualTo(AnonymizeProfileDataUseCase.Result.NOTHING_TO_ANONYMIZE);
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("anonymize_profile_data:nothing");
  }

  // ADR-0021 §6: el borrado físico de los CV se pide en la misma transacción, por la bandeja.
  @Test
  void aCandidateWithCvVersionsAlsoRequestsTheirPhysicalDeletion() {
    candidate = Optional.of(anonymized(4));
    String first = "cv/" + UUID.randomUUID();
    String second = "cv/" + UUID.randomUUID();
    cvKeys = List.of(first, second);

    service.execute(new AnonymizeProfileDataCommand(account));

    assertThat(events)
        .extracting(OutboxEvent::eventType)
        .containsExactly("profile.anonymized", "storage.file_deletion_requested");
    var deletion = events.get(1);
    assertThat(deletion.aggregateId()).isEqualTo(candidate.get().profileId());
    assertThat(deletion.payload()).isEqualTo("{\"keys\":[\"" + first + "\",\"" + second + "\"]}");
    assertThat(deletion.payload()).doesNotContain(account.toString());
  }

  @Test
  void aCandidateWithoutCvRequestsNoDeletion() {
    candidate = Optional.of(anonymized(4));

    service.execute(new AnonymizeProfileDataCommand(account));

    assertThat(events).extracting(OutboxEvent::eventType).containsExactly("profile.anonymized");
  }

  // La foto se descarta antes de anonimizar y su borrado viaja en el mismo evento que el de los CV.
  @Test
  void thePhotoAndTheCvFilesAreDeletedInOneRequestAssociatedToTheRootProfile() {
    professional = Optional.of(anonymized(5));
    candidate = Optional.of(anonymized(9));
    String photo = "photo/" + UUID.randomUUID();
    String cv = "cv/" + UUID.randomUUID();
    photoKey = Optional.of(photo);
    cvKeys = List.of(cv);

    service.execute(new AnonymizeProfileDataCommand(account));

    var deletion = events.get(1);
    assertThat(deletion.eventType()).isEqualTo("storage.file_deletion_requested");
    assertThat(deletion.aggregateId()).isEqualTo(professional.get().profileId());
    assertThat(deletion.payload()).contains(photo, cv);
    assertThat(calls.get(0)).isEqualTo("photo");
  }
}
