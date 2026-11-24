package com.usac.jobseeker.matching.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * La redacción de datos personales antes de embeber (TD-021, ADR-0017 R5): lo que quita, lo que
 * deja y sus límites conocidos. El resultado nunca contiene lo que se quitó.
 */
class PiiRedactorTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Escríbeme a ana.lopez+cv@example.com para detalles|Escríbeme a [correo] para detalles",
        "contacto: ANA@Example.CO.GT.|contacto: [correo].",
        "dos: a@b.io y c.d@e-f.org|dos: [correo] y [correo]",
        "correo con ñandú: peña@dominio.com|correo con ñandú: [correo]",
      })
  void emailsAreRedacted(String input, String expected) {
    assertThat(PiiRedactor.redact(input).text()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "mi perfil https://www.linkedin.com/in/ana-lopez-123 y listo|mi perfil [enlace] y listo",
        "ver www.ejemplo.com/portafolio?x=1 hoy|ver [enlace] hoy",
        "código en github.com/analopez/proyecto aquí|código en [enlace] aquí",
        "http://sitio.example/ruta|[enlace]",
        "escríbeme por wa.me/50255551234 ya|escríbeme por [enlace] ya",
      })
  void linksAreRedacted(String input, String expected) {
    assertThat(PiiRedactor.redact(input).text()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "sígueme en @ana_lopez99 gracias|sígueme en [usuario] gracias",
        "mención @dev_x y @otra_persona|mención [usuario] y [usuario]",
      })
  void handlesAreRedacted(String input, String expected) {
    assertThat(PiiRedactor.redact(input).text()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "llámame al 5555-1234 de lunes a viernes|llámame al [teléfono] de lunes a viernes",
        "tel +502 5555 1234 oficina|tel [teléfono] oficina",
        "cel (502) 5555-1234.|cel [teléfono].",
        "+1 (415) 555-2671|[teléfono]",
        "55551234|[teléfono]",
        "DPI 2456 12345 0101 emitido|DPI [documento] emitido",
        "documento 2456123450101|documento [documento]",
      })
  void phonesAndDocumentsAreRedacted(String input, String expected) {
    assertThat(PiiRedactor.redact(input).text()).isEqualTo(expected);
  }

  // Lo más frecuente en una descripción de experiencia y lo que nunca debe tocarse.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "Backend developer entre 2018-2022 en proyectos de pagos",
        "trabajé 2019 2021 y luego 2021-2023",
        "10 años de experiencia, 3 equipos, 25 microservicios",
        "salario Q12,000 al mes y bono de 1500",
        "ISO 9001:2015 e ISO 27001",
        "versión 3.2.1 de la librería",
        "fecha 12/05/2021 de cierre",
        "",
      })
  void ordinaryTextIsLeftExactlyAsItIs(String text) {
    assertThat(PiiRedactor.redact(text).text()).isEqualTo(text);
    assertThat(PiiRedactor.redact(text).redactions()).isZero();
  }

  @Test
  void aNullTextIsEmptyAndNothingWasRedacted() {
    var result = PiiRedactor.redact(null);

    assertThat(result.text()).isEmpty();
    assertThat(result.redactions()).isZero();
  }

  @Test
  void everyPieceRemovedIsCountedButNeverNamed() {
    var result = PiiRedactor.redact("a@b.co, 5555-1234, www.x.com/y y @usuario");

    assertThat(result.text()).isEqualTo("[correo], [teléfono], [enlace] y [usuario]");
    assertThat(result.redactions()).isEqualTo(4);
  }

  @Test
  void redactingTwiceChangesNothingTheSecondTime() {
    String once = PiiRedactor.redact("a@b.co y 5555-1234 en https://x.com/y").text();

    assertThat(PiiRedactor.redact(once).text()).isEqualTo(once);
    assertThat(PiiRedactor.redact(once).redactions()).isZero();
  }

  @Test
  void aNulCharacterNeverSurvives() {
    assertThat(PiiRedactor.redact("a\u0000b").text()).doesNotContain("\u0000");
  }

  // Límite conocido, documentado en la clase: un rango salarial escrito como un teléfono se
  // redacta.
  @Test
  void aSalaryRangeWrittenLikeAPhoneIsRedactedOnPurposeBecauseLeakingIsWorse() {
    assertThat(PiiRedactor.redact("presupuesto 3000-4500 USD").text())
        .isEqualTo("presupuesto [teléfono] USD");
  }

  // Límite conocido (TD-027): los nombres propios no se redactan.
  @Test
  void personalNamesAreNotRedactedBecauseThatNeedsALanguageModel() {
    assertThat(PiiRedactor.redact("Trabajé con Ana López en el equipo de pagos").text())
        .contains("Ana López");
  }

  // Propiedad con semilla fija: ningún dato personal inyectado sobrevive, sea cual sea el texto de
  // alrededor ni su forma.
  @Test
  void noInjectedPersonalDatumSurvivesInAThousandRandomTexts() {
    Random random = new Random(20261124L);
    String[] words = {
      "desarrollé",
      "servicios",
      "en",
      "Java",
      "con",
      "equipos",
      "de",
      "pagos",
      "y",
      "datos",
      "2020",
      "10",
      "años"
    };
    for (int i = 0; i < 1000; i++) {
      List<String> injected = new ArrayList<>();
      StringBuilder text = new StringBuilder();
      for (int w = 0; w < 12; w++) {
        text.append(words[random.nextInt(words.length)]).append(' ');
        if (random.nextInt(4) == 0) {
          String pii = piece(random);
          injected.add(pii);
          text.append(pii).append(' ');
        }
      }

      String redacted = PiiRedactor.redact(text.toString()).text();

      for (String pii : injected) {
        assertThat(redacted).as("texto %d: %s", i, pii).doesNotContain(pii);
      }
    }
  }

  private static String piece(Random random) {
    return switch (random.nextInt(5)) {
      case 0 -> "usuario" + random.nextInt(10_000) + "@dominio" + random.nextInt(100) + ".com";
      case 1 -> "https://sitio" + random.nextInt(100) + ".example/ruta/" + random.nextInt(1000);
      case 2 -> "5" + (1_000_000 + random.nextInt(8_999_999));
      case 3 -> "@persona_" + (100 + random.nextInt(900));
      default -> "+502 " + (2000 + random.nextInt(7999)) + " " + (1000 + random.nextInt(8999));
    };
  }

  // El costo es lineal: una entrada hostil de cientos de miles de caracteres no cuelga el hilo.
  @Test
  @Timeout(value = 10)
  void hostileInputOfHalfAMillionCharactersIsProcessedInLinearTime() {
    String digits = "1 ".repeat(100_000);
    String dots = "a".repeat(100_000) + "@" + "b.".repeat(50_000);
    String mixed = ("(" + "1-".repeat(20) + ") ").repeat(5_000);

    for (String hostile : List.of(digits, dots, mixed)) {
      long started = System.nanoTime();
      PiiRedactor.redact(hostile);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
  }
}
