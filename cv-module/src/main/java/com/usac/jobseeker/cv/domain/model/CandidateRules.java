package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Los catálogos y las reglas de formato del perfil de candidato que se validan en la aplicación,
 * sin una tabla de catálogo: países (ISO 3166-1 alfa-2), idiomas (ISO 639-1) y monedas (ISO 4217)
 * salen de la lista del propio JDK, que ya los trae (decisión de `tdd/cv-module.md` §3.1 y
 * `data-model.md` §6). Normalizan a la forma canónica (países y monedas en mayúsculas, idiomas en
 * minúsculas).
 */
final class CandidateRules {

  private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
  private static final Set<String> LANGUAGES = Set.of(Locale.getISOLanguages());
  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  static final String REQUIRED = "REQUIRED";
  static final String DUPLICATE = "DUPLICATE";
  static final String TOO_MANY = "TOO_MANY";
  static final String TOO_LONG = "TOO_LONG";
  static final String INVALID_CHARACTERS = "INVALID_CHARACTERS";

  private CandidateRules() {}

  /** El código de país en mayúsculas, o nulo si falta; agrega `INVALID_COUNTRY` si no existe. */
  static String country(
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

  static String language(String field, String raw, List<FieldViolation> violations) {
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

  static String currency(String field, String raw, List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      return null;
    }
    if (!CURRENCIES.contains(value)) {
      violations.add(new FieldViolation(field, "INVALID_CURRENCY"));
    }
    return value;
  }

  /** La clave con la que dos habilidades son «la misma»: sin mayúsculas ni espacios de más. */
  static String sameSkillKey(String name) {
    return name.strip().toLowerCase(Locale.ROOT);
  }
}
