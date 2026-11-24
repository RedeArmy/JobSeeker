package com.usac.jobseeker.matching.domain.model;

import com.usac.jobseeker.matching.domain.service.PiiRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * El único texto que puede enviarse a un modelo de embeddings: ya sin correos, enlaces, usuarios ni
 * cifras de contacto (ADR-0017, riesgo R5; TD-021). La única forma de obtenerlo es {@link #of}, que
 * redacta; no hay constructor público ni forma de envolver un texto sin redactar. Así, el puerto
 * que llame al modelo recibe este tipo y **el compilador** garantiza que nada sin redactar llega a
 * un tercero, sin depender de que cada caso de uso se acuerde.
 *
 * <p>El *hash* del contenido es el de este texto ya redactado: lo que se compara para saber si hay
 * que volver a llamar al modelo (BR-MATCH-03) es lo que realmente se envía.
 */
public final class EmbeddableText {

  private final String redactedText;
  private final int removed;

  private EmbeddableText(String redactedText, int removed) {
    this.redactedText = redactedText;
    this.removed = removed;
  }

  /** Redacta, normaliza los espacios y devuelve el texto listo para embeber. */
  public static EmbeddableText of(String raw) {
    PiiRedactor.Result redacted = PiiRedactor.redact(raw);
    String normalized = redacted.text().replaceAll("\\s+", " ").strip();
    return new EmbeddableText(normalized, redacted.redactions());
  }

  public String value() {
    return redactedText;
  }

  /** Cuántas piezas se quitaron; es una métrica de calidad, nunca se registra su contenido. */
  public int redactions() {
    return removed;
  }

  /** SHA-256 en hexadecimal minúscula del texto redactado. */
  public String contentHash() {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(redactedText.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 siempre está disponible", impossible);
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EmbeddableText text && redactedText.equals(text.redactedText);
  }

  @Override
  public int hashCode() {
    return Objects.hash(redactedText);
  }

  /** Nunca incluye el texto: un registro descuidado no debe volcar el contenido de un perfil. */
  @Override
  public String toString() {
    return "EmbeddableText[" + redactedText.length() + " caracteres]";
  }
}
