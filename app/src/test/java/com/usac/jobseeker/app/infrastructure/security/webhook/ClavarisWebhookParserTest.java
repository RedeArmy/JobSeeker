package com.usac.jobseeker.app.infrastructure.security.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.auth.application.usecase.handleclavariswebhook.HandleClavarisWebhookCommand;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reducir la carga a identificadores: nada de lo demás debe sobrevivir. */
class ClavarisWebhookParserTest {

  private static final String ACCOUNT = "11111111-2222-3333-4444-555555555555";
  private static final String EVENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  private static Optional<HandleClavarisWebhookCommand> parse(String json, String type, String id) {
    return ClavarisWebhookParser.parse(json.getBytes(StandardCharsets.UTF_8), type, id, "trace-9");
  }

  @Test
  void theNestedIdentifierShapeOfRealDeliveriesIsParsed() {
    var command =
        parse(
                "{\"accountId\":{\"value\":\""
                    + ACCOUNT
                    + "\"},\"organizationId\":{\"value\":\"o\"},"
                    + "\"occurredAt\":\"2026-11-22T12:00:00.123456Z\"}",
                "account.suspended",
                EVENT)
            .orElseThrow();

    assertThat(command.accountId().value()).isEqualTo(UUID.fromString(ACCOUNT));
    assertThat(command.eventId()).isEqualTo(UUID.fromString(EVENT));
    assertThat(command.eventType()).isEqualTo("account.suspended");
    assertThat(command.occurredAt()).isEqualTo(Instant.parse("2026-11-22T12:00:00.123456Z"));
    assertThat(command.traceId()).isEqualTo("trace-9");
  }

  @Test
  void aPlainStringIdentifierIsAlsoAccepted() {
    assertThat(
            parse(
                "{\"accountId\":\"" + ACCOUNT + "\",\"occurredAt\":\"2026-11-22T12:00:00Z\"}",
                "t",
                EVENT))
        .isPresent();
  }

  @Test
  void aNumericOccurredAtIsReadAsEpochSecondsWithFraction() {
    var command =
        parse("{\"accountId\":\"" + ACCOUNT + "\",\"occurredAt\":1700000000.250000000}", "t", EVENT)
            .orElseThrow();

    assertThat(command.occurredAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L, 250_000_000));
  }

  @Test
  void personalDataInThePayloadNeverReachesTheCommand() {
    var command =
        parse(
                "{\"accountId\":{\"value\":\""
                    + ACCOUNT
                    + "\"},\"email\":\"alguien@ejemplo.com\","
                    + "\"firstName\":\"Ana\",\"phone\":\"+502-7777-0000\",\"occurredAt\":\"2026-11-22T12:00:00Z\"}",
                "account.deleted",
                EVENT)
            .orElseThrow();

    assertThat(command.toString()).doesNotContain("alguien@ejemplo.com", "Ana", "7777-0000");
  }

  @Test
  void aBlankTraceIdBecomesNull() {
    var command =
        ClavarisWebhookParser.parse(
                ("{\"accountId\":\"" + ACCOUNT + "\",\"occurredAt\":\"2026-11-22T12:00:00Z\"}")
                    .getBytes(StandardCharsets.UTF_8),
                "t",
                EVENT,
                "  ")
            .orElseThrow();

    assertThat(command.traceId()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not json",
        "[]",
        "{}",
        "{\"accountId\":{\"value\":\"" + ACCOUNT + "\"}}",
        "{\"occurredAt\":\"2026-11-22T12:00:00Z\"}",
        "{\"accountId\":{\"value\":\"no-es-uuid\"},\"occurredAt\":\"2026-11-22T12:00:00Z\"}",
        "{\"accountId\":{\"value\":5},\"occurredAt\":\"2026-11-22T12:00:00Z\"}",
        "{\"accountId\":{\"value\":\"" + ACCOUNT + "\"},\"occurredAt\":\"ayer\"}",
        "{\"accountId\":{\"value\":\"" + ACCOUNT + "\"},\"occurredAt\":true}"
      })
  void aBodyWithoutTheExpectedShapeIsRejectedWithoutAnException(String json) {
    assertThat(parse(json, "account.suspended", EVENT)).isEmpty();
  }

  @Test
  void theHeadersAreRequiredAndTheEventIdMustBeAUuid() {
    String ok = "{\"accountId\":\"" + ACCOUNT + "\",\"occurredAt\":\"2026-11-22T12:00:00Z\"}";

    assertThat(parse(ok, null, EVENT)).isEmpty();
    assertThat(parse(ok, "  ", EVENT)).as("el caso de uso lo ignora").isPresent();
    assertThat(parse(ok, "t", null)).isEmpty();
    assertThat(parse(ok, "t", "no-es-uuid")).isEmpty();
  }
}
