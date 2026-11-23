package com.usac.jobseeker.common.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El sobre de un evento de la bandeja: lo mínimo que exige para ser válido. */
class OutboxEventTest {

  private static OutboxEvent event(
      String aggregateType, String eventType, int version, String payload) {
    return new OutboxEvent(
        UUID.randomUUID(), aggregateType, UUID.randomUUID(), 1L, eventType, version, payload, null);
  }

  @Test
  void aWellFormedEventKeepsAllItsFieldsAndTheTraceIdIsOptional() {
    UUID id = UUID.randomUUID();
    UUID aggregate = UUID.randomUUID();

    OutboxEvent event = new OutboxEvent(id, "account", aggregate, 7L, "x.y", 2, "{}", null);

    assertThat(event.id()).isEqualTo(id);
    assertThat(event.aggregateId()).isEqualTo(aggregate);
    assertThat(event.aggregateVersion()).isEqualTo(7L);
    assertThat(event.eventVersion()).isEqualTo(2);
    assertThat(event.traceId()).isNull();
  }

  @Test
  void everyRequiredFieldIsRejectedWhenMissing() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> new OutboxEvent(null, "a", id, 1, "t", 1, "{}", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("id");
    assertThatThrownBy(() -> new OutboxEvent(id, null, id, 1, "t", 1, "{}", null))
        .hasMessageContaining("aggregateType");
    assertThatThrownBy(() -> new OutboxEvent(id, "a", null, 1, "t", 1, "{}", null))
        .hasMessageContaining("aggregateId");
    assertThatThrownBy(() -> new OutboxEvent(id, "a", id, 1, null, 1, "{}", null))
        .hasMessageContaining("eventType");
    assertThatThrownBy(() -> new OutboxEvent(id, "a", id, 1, "t", 1, null, null))
        .hasMessageContaining("payload");
  }

  @Test
  void blankTypesAreRejectedBecauseTheyCouldNeverBeRouted() {
    assertThatThrownBy(() -> event("", "t", 1, "{}")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("a", "  ", 1, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSchemaVersionStartsAtOne() {
    assertThat(event("a", "t", 1, "{}").eventVersion()).isEqualTo(1);
    assertThatThrownBy(() -> event("a", "t", 0, "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empieza en 1");
  }
}
