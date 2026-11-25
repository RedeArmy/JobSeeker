package com.usac.jobseeker.common.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormatRulesTest {

  private final List<FieldViolation> violations = new ArrayList<>();

  private static FieldViolation v(String field, String code) {
    return new FieldViolation(field, code);
  }

  // ---- texto ----

  @Test
  void aSingleLineIsTrimmedAndAnEmptyOneIsAbsentOrRequired() {
    assertThat(FormatRules.singleLine("f", "  Ana  ", 10, true, violations)).isEqualTo("Ana");
    assertThat(FormatRules.singleLine("f", "  ", 10, false, violations)).isNull();
    assertThat(FormatRules.singleLine("f", null, 10, false, violations)).isNull();
    assertThat(violations).isEmpty();

    assertThat(FormatRules.singleLine("f", " ", 10, true, violations)).isNull();
    assertThat(violations).containsExactly(v("f", "REQUIRED"));
  }

  @Test
  void aSingleLineRejectsLineBreaksAndIsLimitedInCodePointsNotChars() {
    FormatRules.singleLine("a", "uno\ndos", 20, true, violations);
    FormatRules.singleLine("b", "x".repeat(11), 10, true, violations);
    // Un emoji son dos `char` pero un solo punto de código: 10 emojis caben en un límite de 10.
    FormatRules.singleLine("c", "😀".repeat(10), 10, true, violations);

    assertThat(violations).containsExactly(v("a", "INVALID_CHARACTERS"), v("b", "TOO_LONG"));
  }

  @Test
  void aMultiLineKeepsLineBreaksAndTabsButNeverTheNullCharacter() {
    assertThat(FormatRules.multiLine("f", "a\nb\tc\r\nd", 50, false, violations))
        .isEqualTo("a\nb\tc\r\nd");
    assertThat(violations).isEmpty();

    FormatRules.multiLine("g", "a\u0000b", 50, false, violations);
    FormatRules.multiLine("h", "x".repeat(51), 50, false, violations);
    FormatRules.multiLine("i", " ", 50, true, violations);

    assertThat(violations)
        .containsExactly(v("g", "INVALID_CHARACTERS"), v("h", "TOO_LONG"), v("i", "REQUIRED"));
  }

  @Test
  void theNullCharacterIsForbiddenEvenWhenLayoutIsAllowed() {
    assertThat(FormatRules.hasForbiddenControl("a\u0000", true)).isTrue();
    assertThat(FormatRules.hasForbiddenControl("a\n", true)).isFalse();
    assertThat(FormatRules.hasForbiddenControl("a\n", false)).isTrue();
    assertThat(FormatRules.hasForbiddenControl("ñandú ✓", false)).isFalse();
  }

  // ---- catálogos ----

  @Test
  void aCountryIsNormalizedToUppercaseAndMustExist() {
    assertThat(FormatRules.country("c", " gt ", false, violations)).isEqualTo("GT");
    assertThat(violations).isEmpty();

    assertThat(FormatRules.country("c", "ZZ", false, violations)).isEqualTo("ZZ");
    assertThat(violations).containsExactly(v("c", "INVALID_COUNTRY"));
  }

  @Test
  void anAbsentCountryIsOnlyAnErrorWhenRequired() {
    assertThat(FormatRules.country("c", null, false, violations)).isNull();
    assertThat(violations).isEmpty();

    assertThat(FormatRules.country("c", " ", true, violations)).isNull();
    assertThat(violations).containsExactly(v("c", "REQUIRED"));
  }

  @Test
  void aLanguageIsNormalizedToLowercaseIsAlwaysRequiredAndMustExist() {
    assertThat(FormatRules.language("l", " EN ", violations)).isEqualTo("en");
    assertThat(violations).isEmpty();

    assertThat(FormatRules.language("l", "", violations)).isNull();
    assertThat(FormatRules.language("m", "zz", violations)).isEqualTo("zz");
    assertThat(violations).containsExactly(v("l", "REQUIRED"), v("m", "INVALID_LANGUAGE"));
  }

  @Test
  void aCurrencyIsNormalizedToUppercaseAndMustExist() {
    assertThat(FormatRules.currency("m", "usd", violations)).isEqualTo("USD");
    assertThat(FormatRules.currency("m", "", violations)).isNull();
    assertThat(violations).isEmpty();

    FormatRules.currency("m", "XXQ", violations);
    assertThat(violations).containsExactly(v("m", "INVALID_CURRENCY"));
  }

  @Test
  void twoNamesThatDifferOnlyInCaseOrSpacesAreTheSame() {
    assertThat(FormatRules.sameKey("  Java ")).isEqualTo(FormatRules.sameKey("jAVA"));
    assertThat(FormatRules.sameKey("Java")).isNotEqualTo(FormatRules.sameKey("Javascript"));
  }

  // Nunca el valor rechazado: puede ser un dato personal (BR-DATA-01).
  @Test
  void aViolationNeverCarriesTheRejectedValue() {
    FormatRules.singleLine("f", "secreto\nmultilínea", 50, true, violations);
    FormatRules.country("c", "SECRETO", false, violations);

    assertThat(violations.toString()).doesNotContain("secreto").doesNotContain("SECRETO");
  }
}
