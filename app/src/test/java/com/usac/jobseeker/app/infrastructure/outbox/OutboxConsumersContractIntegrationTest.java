package com.usac.jobseeker.app.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Lo que se exige a todo consumidor registrado, comprobado sobre los beans reales: así quien añada
 * uno nuevo (los de `matching`, por ejemplo) no puede olvidar lo que la deduplicación necesita.
 */
@SpringBootTest
@Import(InfrastructureTestConfiguration.class)
@WithoutOutboxScheduler
class OutboxConsumersContractIntegrationTest {

  @Autowired private List<OutboxEventConsumer> consumers;

  @Test
  void everyConsumerHasAStableNonBlankNameThatNoOtherConsumerShares() {
    List<String> names = consumers.stream().map(OutboxEventConsumer::name).toList();

    assertThat(names)
        .isNotEmpty()
        .doesNotContainNull()
        .doesNotHaveDuplicates()
        .allSatisfy(name -> assertThat(name).isNotBlank());
  }

  @Test
  void aConsumerHandlesAtLeastOneTypeAndNeverTheEmptyOne() {
    for (OutboxEventConsumer consumer : consumers) {
      assertThat(consumer.handles("")).as(consumer.name()).isFalse();
      assertThat(consumer.handles("tipo.que.nadie.define")).as(consumer.name()).isFalse();
    }
  }
}
