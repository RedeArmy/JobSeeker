package com.usac.jobseeker.app.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Prueba que cada regla de ARCH-01..10 detecta de verdad una violación: con los módulos vacíos las
 * reglas reales pasan sin evaluar nada, así que sin esta prueba una regla rota no se notaría. Cada
 * caso apunta a un paquete de `com.usac.archfixture` (fuera de `com.usac.jobseeker`, para que
 * ArchitectureTest no vea estas clases) que viola exactamente esa regla.
 */
class ArchitectureRulesNegativeTest {

  private static final String FIXTURES = "com.usac.archfixture.";

  @ParameterizedTest(name = "{0} detecta la violación de {1}")
  @CsvSource({
    "ARCH-01, arch01",
    "ARCH-02, arch02",
    "ARCH-03, arch03",
    "ARCH-04, arch04",
    "ARCH-04, arch04b",
    "ARCH-05, arch05",
    "ARCH-05, arch05b",
    "ARCH-06, arch06",
    "ARCH-07, arch07",
    "ARCH-08, arch08",
    "ARCH-08, arch08b",
    "ARCH-09, arch09",
    "ARCH-10, arch10"
  })
  void eachRuleRejectsItsViolation(String ruleId, String fixture) {
    String root = FIXTURES + fixture;
    ArchRule rule = ArchitectureRules.all(root).get(ruleId);
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    assertThatThrownBy(() -> rule.check(classes))
        .as("%s debe fallar sobre %s", ruleId, fixture)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("was violated");
  }

  @Test
  void aCompliantModuleSetPassesEveryRule() {
    String root = FIXTURES + "good";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    Map<String, ArchRule> rules = ArchitectureRules.all(root);
    rules.forEach(
        (id, rule) ->
            assertThatCode(() -> rule.check(classes))
                .as("%s debe pasar sobre el ejemplo conforme", id)
                .doesNotThrowAnyException());
  }
}
