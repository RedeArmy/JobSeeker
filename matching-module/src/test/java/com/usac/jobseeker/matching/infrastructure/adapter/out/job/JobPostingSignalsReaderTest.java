package com.usac.jobseeker.matching.infrastructure.adapter.out.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsQuery;
import com.usac.jobseeker.job.application.usecase.getjobsignals.JobSnapshot;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La capa anticorrupción hacia `job`: traduce su lectura pública al tipo propio de `matching`. */
class JobPostingSignalsReaderTest {

  private final UUID id = UUID.randomUUID();
  private final List<GetJobSignalsQuery> asked = new ArrayList<>();
  private Optional<JobSnapshot> answer = Optional.empty();

  private final JobPostingSignalsReader reader =
      new JobPostingSignalsReader(
          query -> {
            asked.add(query);
            return answer;
          },
          query -> {
            Map<UUID, JobSnapshot> found = new java.util.HashMap<>();
            answer.ifPresent(
                snapshot -> query.jobPostingIds().forEach(i -> found.put(i, snapshot)));
            return found;
          });

  private JobSnapshot snapshot(String status, String stage) {
    return new JobSnapshot(
        id,
        7,
        status,
        stage,
        true,
        "Backend",
        "Servicios",
        "REMOTE",
        "GT",
        new JobSnapshot.Salary(new BigDecimal("3000"), null, "USD", "MONTH"),
        List.of(
            new JobSnapshot.Requirement("SKILL", true, "Java", 3, null, null, null, null),
            new JobSnapshot.Requirement(
                "EDUCATION", false, null, null, "BACHELOR", null, null, null),
            new JobSnapshot.Requirement("EXPERIENCE_YEARS", false, null, null, null, 5, null, null),
            new JobSnapshot.Requirement("LANGUAGE", false, null, null, null, null, "en", "B2")));
  }

  @Test
  void aPublishedJobInHiringIsTranslatedWithEveryRequirement() {
    answer = Optional.of(snapshot("PUBLISHED", "HIRING"));

    var signals = reader.read(new JobPostingId(id)).orElseThrow();

    assertThat(signals.jobId().value()).isEqualTo(id);
    assertThat(signals.version()).isEqualTo(7);
    assertThat(signals.published()).isTrue();
    assertThat(signals.reviewing()).isFalse();
    assertThat(signals.title()).isEqualTo("Backend");
    assertThat(signals.description()).isEqualTo("Servicios");
    assertThat(signals.requirements()).hasSize(4);
    assertThat(signals.requirements().get(0).skillName()).isEqualTo("Java");
    assertThat(signals.requirements().get(0).minLevel()).isEqualTo(3);
    assertThat(signals.requirements().get(1).educationLevel()).isEqualTo("BACHELOR");
    assertThat(signals.requirements().get(2).minYears()).isEqualTo(5);
    assertThat(signals.requirements().get(3).minProficiency()).isEqualTo("B2");
    assertThat(asked).singleElement().satisfies(q -> assertThat(q.jobPostingId()).isEqualTo(id));
  }

  @Test
  void aJobInReviewingIsMarkedAsSuch() {
    answer = Optional.of(snapshot("PUBLISHED", "REVIEWING"));

    assertThat(reader.read(new JobPostingId(id)).orElseThrow().reviewing()).isTrue();
  }

  // Ante un estado que no conoce, la vacante no se considera publicada: ante la duda no se puntúa.
  @Test
  void onlyThePublishedStatusCountsAsPublished() {
    for (String status : List.of("CLOSED", "ARCHIVED", "DRAFT", "ALGO_NUEVO", "published")) {
      answer = Optional.of(snapshot(status, "HIRING"));

      assertThat(reader.read(new JobPostingId(id)).orElseThrow().published()).as(status).isFalse();
    }
  }

  @Test
  void whatJobDoesNotDeliverIsNotDelivered() {
    assertThat(reader.read(new JobPostingId(id))).isEmpty();
  }

  // job nunca entrega al reclutador, y el tipo propio no tiene dónde guardarlo. Sí lleva la
  // modalidad, el país y el salario, que el puntaje compara (no entran al texto que se embebe).
  @Test
  void theTranslatedSignalsCarryTheStructuralDimensionsButNothingAboutTheRecruiter() {
    answer = Optional.of(snapshot("PUBLISHED", "HIRING"));

    var signals = reader.read(new JobPostingId(id)).orElseThrow();

    assertThat(signals.modality()).isEqualTo("REMOTE");
    assertThat(signals.country()).isEqualTo("GT");
    assertThat(signals.salary().min()).isEqualByComparingTo("3000");
    assertThat(
            java.util.Arrays.stream(
                    com.usac.jobseeker.matching.domain.model.JobSignals.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase(java.util.Locale.ROOT)))
        .noneMatch(n -> n.contains("recruiter") || n.contains("owner") || n.contains("account"));
  }
}
