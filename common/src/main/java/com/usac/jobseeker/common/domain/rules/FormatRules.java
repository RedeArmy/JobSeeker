package com.usac.jobseeker.common.domain.rules;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reglas de formato que comparten los módulos para validar lo que escribe una persona: textos,
 * países (ISO 3166-1 alfa-2), idiomas (ISO 639-1) y monedas (ISO 4217). Los catálogos salen de la
 * lista del propio JDK, sin tabla de catálogo (`tdd/cv-module.md` §3.1). Estaban copiadas en `cv` y
 * en `job`; una corrección en una no llegaba a la otra (TD-031).
 *
 * <p>Cada regla agrega su violación a la lista en vez de lanzar, para que quien valida reúna
 * **todos** los errores de un formulario y los devuelva juntos. Normalizan a la forma canónica
 * (países y monedas en mayúsculas, idiomas en minúsculas) y **nunca** incluyen el valor rechazado,
 * que puede ser un dato personal (BR-DATA-01).
 */
public final class FormatRules {

  // Códigos estables de violación: los usa la API y los traduce la pantalla.
  public static final String REQUIRED = "REQUIRED";
  public static final String TOO_LONG = "TOO_LONG";
  public static final String TOO_MANY = "TOO_MANY";
  public static final String DUPLICATE = "DUPLICATE";
  public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
  public static final String INVALID_CHARACTERS = "INVALID_CHARACTERS";

  private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
  private static final Set<String> LANGUAGES = Set.of(Locale.getISOLanguages());
  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  private FormatRules() {}

  /** Una sola línea; vacía equivale a no haberla dado. Devuelve el valor recortado o nulo. */
  public static String singleLine(
      String field, String raw, int max, boolean required, List<FieldViolation> violations) {
    return text(field, raw, max, required, false, violations);
  }

  /** Texto de varias líneas, con los saltos de línea y las tabulaciones permitidos. */
  public static String multiLine(
      String field, String raw, int max, boolean required, List<FieldViolation> violations) {
    return text(field, raw, max, required, true, violations);
  }

  private static String text(
      String field,
      String raw,
      int max,
      boolean required,
      boolean allowLayout,
      List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty()) {
      if (required) {
        violations.add(new FieldViolation(field, REQUIRED));
      }
      return null;
    }
    // Un salto de línea en un nombre o título no tiene sentido y rompe listados y correos.
    if (hasForbiddenControl(value, allowLayout)) {
      violations.add(new FieldViolation(field, INVALID_CHARACTERS));
    } else if (value.codePointCount(0, value.length()) > max) {
      violations.add(new FieldViolation(field, TOO_LONG));
    }
    return value;
  }

  // PostgreSQL no admite el carácter NUL en un `text`: sin esta regla llegaría como un 500 en vez
  // de un 400. Se rechazan todos los de control salvo, si se permite, los saltos de línea y las
  // tabulaciones de un texto de varias líneas.
  public static boolean hasForbiddenControl(String value, boolean allowLayout) {
    return value
        .codePoints()
        .anyMatch(
            c ->
                Character.isISOControl(c)
                    && !(allowLayout && (c == '\n' || c == '\r' || c == '\t')));
  }

  /** El código de país en mayúsculas, o nulo si falta; agrega `INVALID_COUNTRY` si no existe. */
  public static String country(
      String field, String raw, boolean required, List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      if (required) {
        violations.add(new FieldViolation(field, REQUIRED));
      }
      return null;
    }
    if (!COUNTRIES.contains(value)) {
      violations.add(new FieldViolation(field, "INVALID_COUNTRY"));
    }
    return value;
  }

  /** El código de idioma en minúsculas; obligatorio, y `INVALID_LANGUAGE` si no existe. */
  public static String language(String field, String raw, List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    if (value.isEmpty()) {
      violations.add(new FieldViolation(field, REQUIRED));
      return null;
    }
    if (!LANGUAGES.contains(value)) {
      violations.add(new FieldViolation(field, "INVALID_LANGUAGE"));
    }
    return value;
  }

  /** La moneda en mayúsculas, o nulo si falta; agrega `INVALID_CURRENCY` si no existe. */
  public static String currency(String field, String raw, List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      return null;
    }
    if (!CURRENCIES.contains(value)) {
      violations.add(new FieldViolation(field, "INVALID_CURRENCY"));
    }
    return value;
  }

  /** La clave con la que dos nombres son «el mismo»: sin mayúsculas ni espacios de más. */
  public static String sameKey(String name) {
    return name.strip().toLowerCase(Locale.ROOT);
  }
}
