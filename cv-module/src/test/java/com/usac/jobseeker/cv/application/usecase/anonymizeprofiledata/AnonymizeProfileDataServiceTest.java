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
        .containsExactly("professional:Candidato eliminado:" + NOW, "candidate:" + NOW);
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
}
