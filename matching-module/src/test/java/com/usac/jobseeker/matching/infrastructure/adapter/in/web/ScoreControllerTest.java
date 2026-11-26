package com.usac.jobseeker.matching.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreQuery;
import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreResult;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreQuery;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreResult;
import com.usac.jobseeker.matching.domain.model.AlgorithmVersion;
import com.usac.jobseeker.matching.domain.model.CompatibilityScore;
import com.usac.jobseeker.matching.domain.model.DataStatus;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import com.usac.jobseeker.matching.domain.model.ScoreBreakdown;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * El adaptador solo traduce: el candidato sale del token y la respuesta no lleva el coseno crudo.
 */
class ScoreControllerTest {

  private static final AlgorithmVersion VERSION =
      new AlgorithmVersion("1", "modelo", "a".repeat(64), "b".repeat(64));

  private final UUID caller = UUID.randomUUID();
  private final UUID job = UUID.randomUUID();
  private final Principal principal = caller::toString;
  private final List<ExplainScoreQuery> explained = new ArrayList<>();
  private final List<WhatIfScoreQuery> tried = new ArrayList<>();
  private ExplainScoreResult explainAnswer;
  private WhatIfScoreResult whatIfAnswer;

  private final ScoreController controller =
      new ScoreController(
          query -> {
            explained.add(query);
            return explainAnswer;
          },
          query -> {
            tried.add(query);
            return whatIfAnswer;
          });

  private static CompatibilityScore score(double overall) {
    var dimension =
        new DimensionScore(
            Dimension.SKILLS,
            DataStatus.AVAILABLE,
            0.8,
            1.0,
            "Cubre 4 de 5 habilidades requeridas.");
    return new CompatibilityScore(
        overall, new ScoreBreakdown(List.of(dimension), true, 0.8, 0.6, 0.3, 0.9), VERSION, false);
  }

  @Test
  void theCandidateComesFromTheTokenAndAnAnswerIsOk() {
    explainAnswer = new ExplainScoreResult(ExplainScoreResult.Status.OK, score(78.4), false);

    var response = controller.score(principal, job.toString());

    assertThat(explained)
        .singleElement()
        .satisfies(
            q -> {
              assertThat(q.candidate().value()).isEqualTo(caller);
              assertThat(q.job().value()).isEqualTo(job);
            });
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = response.getBody();
    assertThat(body.status()).isEqualTo("OK");
    assertThat(body.algorithmVersion()).isEqualTo(VERSION.value());
    assertThat(body.calibrated()).isFalse();
    assertThat(body.updating()).isFalse();
    assertThat(body.score().overall()).isEqualTo(78);
    assertThat(body.score().dimensions())
        .singleElement()
        .satisfies(d -> assertThat(d.explanation()).contains("4 de 5"));
  }

  @Test
  void aPendingScoreIsAcceptedWithNoScoreAndNoVersion() {
    explainAnswer = new ExplainScoreResult(ExplainScoreResult.Status.PENDING_EMBEDDING, null, true);

    var response = controller.score(principal, job.toString());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody().status()).isEqualTo("PENDING_EMBEDDING");
    assertThat(response.getBody().score()).isNull();
    assertThat(response.getBody().algorithmVersion()).isNull();
    assertThat(response.getBody().updating()).isTrue();
  }

  @Test
  void theUpdatingFlagIsPassedThrough() {
    explainAnswer = new ExplainScoreResult(ExplainScoreResult.Status.OK, score(50), true);

    assertThat(controller.score(principal, job.toString()).getBody().updating()).isTrue();
  }

  @Test
  void aJobIdThatIsNotAUuidIsAJobThatDoesNotExist() {
    assertThatThrownBy(() -> controller.score(principal, "no-es-un-uuid"))
        .isInstanceOf(NotFoundException.class);
    var request = new ScoreController.WhatIfRequest(null, null);

    assertThatThrownBy(() -> controller.whatIf(principal, "no-es-un-uuid", request))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theWhatIfTranslatesTheHypothesesAndReturnsTheDelta() {
    whatIfAnswer = new WhatIfScoreResult(WhatIfScoreResult.Status.OK, score(50.0), score(62.4));

    var response =
        controller.whatIf(
            principal,
            job.toString(),
            new ScoreController.WhatIfRequest(
                List.of(new ScoreController.WhatIfRequest.SkillInput("Kubernetes", 3)),
                List.of(new ScoreController.WhatIfRequest.LanguageInput("en", "B2"))));

    assertThat(tried)
        .singleElement()
        .satisfies(
            q -> {
              assertThat(q.candidate().value()).isEqualTo(caller);
              assertThat(q.skills())
                  .singleElement()
                  .satisfies(s -> assertThat(s.name()).isEqualTo("Kubernetes"));
              assertThat(q.languages())
                  .singleElement()
                  .satisfies(l -> assertThat(l.proficiency()).isEqualTo("B2"));
            });
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().current().overall()).isEqualTo(50);
    assertThat(response.getBody().hypothetical().overall()).isEqualTo(62);
    assertThat(response.getBody().delta()).isEqualTo(12);
    assertThat(response.getBody().calibrated()).isFalse();
  }

  @Test
  void aWhatIfWithNothingToTryAndAPendingAnswer() {
    whatIfAnswer = new WhatIfScoreResult(WhatIfScoreResult.Status.PENDING_EMBEDDING, null, null);

    var response =
        controller.whatIf(principal, job.toString(), new ScoreController.WhatIfRequest(null, null));

    assertThat(tried.get(0).skills()).isEmpty();
    assertThat(tried.get(0).languages()).isEmpty();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody().current()).isNull();
    assertThat(response.getBody().delta()).isNull();
  }

  // ADR-0017: el coseno bruto nunca llega a una respuesta, ni la cuenta ni datos de contacto.
  @Test
  void theResponseTypesHaveNowhereToCarryTheRawCosineTheAccountOrContactData() {
    List<String> names = new ArrayList<>();
    for (Class<?> type : new Class<?>[] {ScoreResponse.class, ScoreResponse.WhatIf.class}) {
      for (var component : type.getRecordComponents()) {
        names.add(component.getName().toLowerCase(java.util.Locale.ROOT));
      }
    }

    assertThat(names)
        .noneMatch(
            n ->
                n.contains("cosine")
                    || n.contains("similarity")
                    || n.contains("account")
                    || n.contains("email")
                    || n.contains("phone"));
  }
}
