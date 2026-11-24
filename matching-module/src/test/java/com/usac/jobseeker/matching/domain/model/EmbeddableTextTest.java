package com.usac.jobseeker.matching.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * El único texto que sale hacia el modelo: siempre redactado, y no hay otra forma de construirlo.
 */
class EmbeddableTextTest {

  @Test
  void theTextIsRedactedAndItsWhitespaceNormalized() {
    var text = EmbeddableText.of("  Contacto:\n\n  ana@example.com   o  5555-1234  ");

    assertThat(text.value()).isEqualTo("Contacto: [correo] o [teléfono]");
    assertThat(text.redactions()).isEqualTo(2);
  }

  @Test
  void theHashIsOfTheRedactedTextSoTwoTextsDifferingOnlyInContactDataAreTheSame() {
    var one = EmbeddableText.of("Java y SQL, escríbeme a una@example.com");
    var other = EmbeddableText.of("Java y SQL, escríbeme a otra@example.org");

    assertThat(one.contentHash()).isEqualTo(other.contentHash()).matches("[0-9a-f]{64}");
    assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    assertThat(one.contentHash()).isNotEqualTo(EmbeddableText.of("Java y SQL").contentHash());
  }

  @Test
  void aNullOrBlankTextIsAnEmptyOne() {
    assertThat(EmbeddableText.of(null).value()).isEmpty();
    assertThat(EmbeddableText.of("   \n ").value()).isEmpty();
  }

  @Test
  void itsStringFormNeverShowsTheTextSoALogLineCannotDumpAProfile() {
    var text = EmbeddableText.of("Java y SQL en proyectos de pagos");

    assertThat(text.toString()).doesNotContain("Java").contains("caracteres");
  }

  // Lo que hace que la garantía sea del compilador: no hay constructor ni fábrica que acepten texto
  // ya hecho, solo `of`, que redacta.
  @Test
  void thereIsNoWayToBuildItWithoutRedacting() {
    boolean publicConstructor =
        Arrays.stream(EmbeddableText.class.getDeclaredConstructors())
            .anyMatch(c -> Modifier.isPublic(c.getModifiers()));
    long publicStaticFactories =
        Arrays.stream(EmbeddableText.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
            .count();

    assertThat(publicConstructor).isFalse();
    assertThat(publicStaticFactories).isOne();
    assertThat(Modifier.isFinal(EmbeddableText.class.getModifiers())).isTrue();
  }

  @Test
  void theEmbeddingPortOnlyAcceptsTheRedactedType() throws Exception {
    var method =
        Class.forName(
                "com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingClientPort")
            .getMethod("embed", EmbeddableText.class);

    assertThat(method.getParameterTypes()).containsExactly(EmbeddableText.class);
  }
}
