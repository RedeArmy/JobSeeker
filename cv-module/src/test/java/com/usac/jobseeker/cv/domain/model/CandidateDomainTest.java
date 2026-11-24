package com.usac.jobseeker.cv.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Las reglas del perfil de candidato: todo error a la vez y nunca el valor rechazado. */
class CandidateDomainTest {

  private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

  private static CandidateDetails details(
      String email,
      String country,
      BigDecimal min,
      BigDecimal max,
      String currency,
      SalaryPeriod period) {
    return CandidateDetails.of(
        email,
        "+502 5555-1234",
        country,
        WorkModality.REMOTE,
        WorkSchedule.FULL_TIME,
        true,
        min,
        max,
        currency,
        period);
  }

  private static List<FieldViolation> violationsOf(Supplier<?> construction) {
    return org.junit.jupiter.api.Assertions.assertThrows(
            ValidationFailedException.class, construction::get)
        .violations();
  }

  private static BigDecimal n(String value) {
    return new BigDecimal(value);
  }

  // ---- contacto ----

  @Test
  void theContactIsTrimmedAndThePhoneIsOptional() {
    var d =
        CandidateDetails.of(
            "  ana@example.test ", "   ", null, null, null, null, null, null, null, null);

    assertThat(d.contact().email()).isEqualTo("ana@example.test");
    assertThat(d.contact().phone()).isNull();
    assertThat(d.relocationWillingness()).isFalse();
    assertThat(d.salary()).isNull();
  }

  @Test
  void theEmailIsRequired() {
    assertThat(violationsOf(() -> details(null, null, null, null, null, null)))
        .containsExactly(new FieldViolation("contactEmail", "REQUIRED"));
    assertThat(violationsOf(() -> details("  ", null, null, null, null, null)))
        .containsExactly(new FieldViolation("contactEmail", "REQUIRED"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sin-arroba",
        "@example.test",
        "ana@",
        "ana@example",
        "an a@example.test",
        "ana@@example.test",
        "ana@example.test\nbcc:x@y.z"
      })
  void anEmailThatIsNotShapedLikeOneIsRejected(String email) {
    assertThat(violationsOf(() -> details(email, null, null, null, null, null)))
        .containsExactly(new FieldViolation("contactEmail", "INVALID_EMAIL"));
  }

  @Test
  void aTooLongEmailIsRejectedAsLong() {
    String email = "a".repeat(250) + "@example.test";

    assertThat(violationsOf(() -> details(email, null, null, null, null, null)))
        .containsExactly(new FieldViolation("contactEmail", "TOO_LONG"));
  }

  @Test
  void aPhoneAcceptsDigitsAndSeparatorsOnly() {
    assertThat(
            CandidateDetails.of(
                    "a@b.co", "(502) 5555-1234", null, null, null, null, null, null, null, null)
                .contact()
                .phone())
        .isEqualTo("(502) 5555-1234");
    assertThat(
            violationsOf(
                () ->
                    CandidateDetails.of(
                        "a@b.co", "llámame", null, null, null, null, null, null, null, null)))
        .containsExactly(new FieldViolation("contactPhone", "INVALID_PHONE"));
    assertThat(
            violationsOf(
                () ->
                    CandidateDetails.of(
                        "a@b.co", "1".repeat(31), null, null, null, null, null, null, null, null)))
        .containsExactly(new FieldViolation("contactPhone", "TOO_LONG"));
  }

  // ---- país ----

  @Test
  void theCountryIsNormalizedToUpperCaseAndMustBeAnIsoCode() {
    assertThat(details("a@b.co", " gt ", null, null, null, null).countryOfResidence())
        .isEqualTo("GT");
    assertThat(violationsOf(() -> details("a@b.co", "XX", null, null, null, null)))
        .containsExactly(new FieldViolation("countryOfResidence", "INVALID_COUNTRY"));
    assertThat(violationsOf(() -> details("a@b.co", "Guatemala", null, null, null, null)))
        .containsExactly(new FieldViolation("countryOfResidence", "INVALID_COUNTRY"));
  }

  // ---- salario: todo o nada (BR-CAND-07) ----

  @Test
  void aCompleteSalaryKeepsItsCurrencyInUpperCaseAndItsPeriod() {
    var salary =
        details("a@b.co", null, n("3000"), n("4500.50"), " usd ", SalaryPeriod.MONTH).salary();

    assertThat(salary)
        .isEqualTo(new SalaryExpectation(n("3000"), n("4500.50"), "USD", SalaryPeriod.MONTH));
  }

  @Test
  void aSingleBoundIsEnoughAndAnExactValueUsesEqualBounds() {
    assertThat(details("a@b.co", null, n("3000"), null, "GTQ", SalaryPeriod.YEAR).salary().max())
        .isNull();
    assertThat(details("a@b.co", null, null, n("5000"), "GTQ", SalaryPeriod.YEAR).salary().min())
        .isNull();
    assertThat(details("a@b.co", null, n("4000"), n("4000"), "GTQ", SalaryPeriod.MONTH).salary())
        .isNotNull();
  }

  @Test
  void amountsAreKeptWithTwoDecimalsSoTheSameDataIsAlwaysEqual() {
    var plain = details("a@b.co", null, n("4500"), n("5000.5"), "USD", SalaryPeriod.MONTH);
    var padded = details("a@b.co", null, n("4500.00"), n("5000.50"), "USD", SalaryPeriod.MONTH);

    assertThat(plain).isEqualTo(padded);
    assertThat(plain.salary().min()).hasToString("4500.00");
    assertThat(new SalaryExpectation(n("1E+3"), null, "USD", SalaryPeriod.YEAR).min())
        .hasToString("1000.00");
  }

  @Test
  void noSalaryDataAtAllMeansNoSalary() {
    assertThat(details("a@b.co", null, null, null, null, null).salary()).isNull();
    assertThat(details("a@b.co", null, null, null, "  ", null).salary()).isNull();
  }

  @Test
  void anAmountWithoutCurrencyAndPeriodIsRejectedBothAtOnce() {
    assertThat(violationsOf(() -> details("a@b.co", null, n("3000"), null, null, null)))
        .containsExactlyInAnyOrder(
            new FieldViolation("salaryCurrency", "REQUIRED"),
            new FieldViolation("salaryPeriod", "REQUIRED"));
  }

  @Test
  void aCurrencyOrAPeriodWithoutAnAmountIsRejected() {
    assertThat(violationsOf(() -> details("a@b.co", null, null, null, "USD", null)))
        .containsExactlyInAnyOrder(
            new FieldViolation("salaryMin", "REQUIRED"),
            new FieldViolation("salaryPeriod", "REQUIRED"));
    assertThat(violationsOf(() -> details("a@b.co", null, null, null, null, SalaryPeriod.YEAR)))
        .containsExactlyInAnyOrder(
            new FieldViolation("salaryMin", "REQUIRED"),
            new FieldViolation("salaryCurrency", "REQUIRED"));
  }

  @Test
  void theRangeCannotBeInvertedOrNegativeOrAbsurdlyLarge() {
    assertThat(
            violationsOf(
                () -> details("a@b.co", null, n("5000"), n("3000"), "USD", SalaryPeriod.MONTH)))
        .containsExactly(new FieldViolation("salaryMax", "RANGE_INVERTED"));
    assertThat(
            violationsOf(() -> details("a@b.co", null, n("-1"), null, "USD", SalaryPeriod.MONTH)))
        .containsExactly(new FieldViolation("salaryMin", "OUT_OF_RANGE"));
    assertThat(
            violationsOf(
                () -> details("a@b.co", null, n("1000000000000"), null, "USD", SalaryPeriod.MONTH)))
        .containsExactly(new FieldViolation("salaryMin", "OUT_OF_RANGE"));
  }

  @Test
  void anAmountWithMoreThanTwoDecimalsIsRejectedButTrailingZerosAreHarmless() {
    assertThat(
            violationsOf(
                () -> details("a@b.co", null, n("3000.123"), null, "USD", SalaryPeriod.MONTH)))
        .containsExactly(new FieldViolation("salaryMin", "TOO_MANY_DECIMALS"));
    assertThat(details("a@b.co", null, n("3000.500"), null, "USD", SalaryPeriod.MONTH).salary())
        .isNotNull();
  }

  @Test
  void aCurrencyThatDoesNotExistIsRejected() {
    assertThat(violationsOf(() -> details("a@b.co", null, n("1"), null, "ZZZ", SalaryPeriod.MONTH)))
        .containsExactly(new FieldViolation("salaryCurrency", "INVALID_CURRENCY"));
  }

  @Test
  void everyProblemOfTheWholeFormIsReportedTogether() {
    assertThat(violationsOf(() -> details("mal", "XX", n("9"), n("1"), "ZZZ", null)))
        .extracting(FieldViolation::field)
        .containsExactlyInAnyOrder(
            "contactEmail", "countryOfResidence", "salaryCurrency", "salaryPeriod", "salaryMax");
  }

  @Test
  void theMessageNeverContainsTheRejectedValue() {
    assertThatThrownBy(() -> details("secreto-privado", null, null, null, null, null))
        .hasMessageNotContaining("secreto");
  }

  // ---- el perfil ----

  @Test
  void aNewProfileIsDiscoverableWithUnsetAvailabilityAtVersionOne() {
    var profile =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            new AccountId(UUID.randomUUID()),
            details("a@b.co", null, null, null, null, null),
            T0);

    assertThat(profile.discoverable()).isTrue();
    assertThat(profile.availability()).isEqualTo(AvailabilityStatus.UNSET);
    assertThat(profile.version()).isEqualTo(1);
  }

  @Test
  void editingChangesTheDataAndTheVersionButNeverTheDiscoverabilityOrTheAvailability() {
    var base =
        new CandidateProfile(
            new CandidateProfileId(UUID.randomUUID()),
            new AccountId(UUID.randomUUID()),
            details("a@b.co", null, null, null, null, null),
            false,
            AvailabilityStatus.OPEN_TO_WORK,
            4,
            T0);

    var edited = base.edit(details("otro@b.co", "GT", null, null, null, null), T0.plusSeconds(60));

    assertThat(edited.version()).isEqualTo(5);
    assertThat(edited.details().contact().email()).isEqualTo("otro@b.co");
    assertThat(edited.discoverable()).isFalse();
    assertThat(edited.availability()).isEqualTo(AvailabilityStatus.OPEN_TO_WORK);
    assertThat(base.version()).isEqualTo(4);
    assertThat(base.touched(T0).version()).isEqualTo(5);
    assertThat(base.touched(T0).details()).isEqualTo(base.details());
  }

  @Test
  void changingDiscoverabilityOrAvailabilityAdvancesTheVersionAndTouchesNothingElse() {
    var base =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            new AccountId(UUID.randomUUID()),
            details("a@b.co", null, null, null, null, null),
            T0);

    var hidden = base.withDiscoverable(false, T0.plusSeconds(1));
    var open = hidden.withAvailability(AvailabilityStatus.OPEN_TO_WORK, T0.plusSeconds(2));

    assertThat(hidden.discoverable()).isFalse();
    assertThat(hidden.availability()).isEqualTo(AvailabilityStatus.UNSET);
    assertThat(open.discoverable()).isFalse();
    assertThat(open.availability()).isEqualTo(AvailabilityStatus.OPEN_TO_WORK);
    assertThat(open.version()).isEqualTo(3);
    assertThat(open.details()).isEqualTo(base.details());
    assertThatThrownBy(() -> base.withAvailability(null, T0))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nobodyCanExcludeThemselvesButAnyOtherAccountCanBeExcluded() {
    var me = new AccountId(UUID.randomUUID());
    var other = new AccountId(UUID.randomUUID());

    assertThat(AvailabilityExclusion.of(me, other, T0))
        .isEqualTo(new AvailabilityExclusion(other, T0));
    assertThat(violationsOf(() -> AvailabilityExclusion.of(me, me, T0)))
        .containsExactly(new FieldViolation("accountId", "SELF_EXCLUSION"));
  }

  @Test
  void aSavedProfileCannotHaveVersionZeroAndNeedsEveryPart() {
    var id = new CandidateProfileId(UUID.randomUUID());
    var account = new AccountId(UUID.randomUUID());
    var details = details("a@b.co", null, null, null, null, null);

    assertThatThrownBy(
            () -> new CandidateProfile(id, account, details, true, AvailabilityStatus.UNSET, 0, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CandidateProfile(null, account, details, true, AvailabilityStatus.UNSET, 1, T0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new CandidateProfile(id, account, null, true, AvailabilityStatus.UNSET, 1, T0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CandidateProfileId(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void theLanguageLevelsAreOrderedFromLowestToHighestBecauseEligibilityComparesThem() {
    assertThat(LanguageProficiency.values())
        .containsExactly(
            LanguageProficiency.A1,
            LanguageProficiency.A2,
            LanguageProficiency.B1,
            LanguageProficiency.B2,
            LanguageProficiency.C1,
            LanguageProficiency.C2,
            LanguageProficiency.NATIVE);
    assertThat(LanguageProficiency.C1).isGreaterThan(LanguageProficiency.B2);
  }

  // ---- habilidades ----

  @Test
  void skillsAreTrimmedKeepTheirOrderAndTheirOptionalLevel() {
    var skills =
        CandidateSkill.setOf(
            List.of(new CandidateSkill(" Java ", 4), new CandidateSkill("SQL", null)));

    assertThat(skills)
        .containsExactly(new CandidateSkill("Java", 4), new CandidateSkill("SQL", null));
  }

  @Test
  void anEmptySetIsValidBecauseReplacingWithNothingClearsTheSet() {
    assertThat(CandidateSkill.setOf(List.of())).isEmpty();
    assertThat(CandidateLanguage.setOf(List.of())).isEmpty();
    assertThat(WorkAuthorizations.setOf(List.of())).isEmpty();
  }

  @Test
  void aSkillWrittenTwiceIsRejectedEvenIfTheCaseOrTheSpacesDiffer() {
    assertThat(
            violationsOf(
                () ->
                    CandidateSkill.setOf(
                        List.of(new CandidateSkill("Java", 3), new CandidateSkill("  JAVA ", 2)))))
        .containsExactly(new FieldViolation("skills[1].name", "DUPLICATE"));
  }

  @Test
  void skillProblemsNameTheExactElementAndAreReportedTogether() {
    assertThat(
            violationsOf(
                () ->
                    CandidateSkill.setOf(
                        List.of(
                            new CandidateSkill("", 9),
                            new CandidateSkill("x".repeat(101), 1),
                            new CandidateSkill("Ok", 0)))))
        .containsExactlyInAnyOrder(
            new FieldViolation("skills[0].name", "REQUIRED"),
            new FieldViolation("skills[0].level", "OUT_OF_RANGE"),
            new FieldViolation("skills[1].name", "TOO_LONG"),
            new FieldViolation("skills[2].level", "OUT_OF_RANGE"));
  }

  @Test
  void aProfileHoldsAtMostOneHundredSkills() {
    var hundred =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(i -> new CandidateSkill("S" + i, null))
            .toList();
    var moreThanThat =
        java.util.stream.IntStream.range(0, 101)
            .mapToObj(i -> new CandidateSkill("S" + i, null))
            .toList();

    assertThat(CandidateSkill.setOf(hundred)).hasSize(100);
    assertThat(violationsOf(() -> CandidateSkill.setOf(moreThanThat)))
        .contains(new FieldViolation("skills", "TOO_MANY"));
  }

  // ---- idiomas ----

  @Test
  void languagesAreNormalizedToLowerCaseAndMustBeIsoCodes() {
    assertThat(
            CandidateLanguage.setOf(
                List.of(
                    new CandidateLanguage(" ES ", LanguageProficiency.NATIVE),
                    new CandidateLanguage("en", LanguageProficiency.B2))))
        .containsExactly(
            new CandidateLanguage("es", LanguageProficiency.NATIVE),
            new CandidateLanguage("en", LanguageProficiency.B2));
    assertThat(
            violationsOf(
                () ->
                    CandidateLanguage.setOf(
                        List.of(new CandidateLanguage("zz9", LanguageProficiency.A1)))))
        .containsExactly(new FieldViolation("languages[0].language", "INVALID_LANGUAGE"));
  }

  @Test
  void aLanguageNeedsItsLevelAndCannotRepeat() {
    assertThat(
            violationsOf(
                () ->
                    CandidateLanguage.setOf(
                        List.of(
                            new CandidateLanguage("en", null),
                            new CandidateLanguage(null, LanguageProficiency.A1)))))
        .containsExactlyInAnyOrder(
            new FieldViolation("languages[0].proficiency", "REQUIRED"),
            new FieldViolation("languages[1].language", "REQUIRED"));
    assertThat(
            violationsOf(
                () ->
                    CandidateLanguage.setOf(
                        List.of(
                            new CandidateLanguage("en", LanguageProficiency.B1),
                            new CandidateLanguage("EN", LanguageProficiency.C1)))))
        .containsExactly(new FieldViolation("languages[1].language", "DUPLICATE"));
  }

  // ---- autorizaciones de trabajo ----

  @Test
  void workAuthorizationsAreNormalizedAndValidatedAsIsoCountries() {
    assertThat(WorkAuthorizations.setOf(List.of(" gt", "MX"))).containsExactly("GT", "MX");
    assertThat(violationsOf(() -> WorkAuthorizations.setOf(List.of("GT", "gt", "QQ", " "))))
        .containsExactlyInAnyOrder(
            new FieldViolation("workAuthorizations[1]", "DUPLICATE"),
            new FieldViolation("workAuthorizations[2]", "INVALID_COUNTRY"),
            new FieldViolation("workAuthorizations[3]", "REQUIRED"));
  }

  @Test
  void theSetsAreImmutableCopies() {
    var source = new java.util.ArrayList<>(List.of(new CandidateSkill("Java", null)));
    var collections = new CandidateCollections(source, List.of(), List.of());
    source.clear();

    assertThat(collections.skills()).hasSize(1);
    var skills = collections.skills();
    assertThatThrownBy(skills::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThat(CandidateCollections.empty().languages()).isEmpty();
  }
}
