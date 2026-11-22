package com.usac.jobseeker.app.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ADR-0015: las reglas ARCH-01..14 sobre el código real, en cada build. Una violación rompe `mvn
 * verify`; no hay modo advertencia. Mientras los módulos estén vacíos pasan sin evaluar clases: que
 * además detectan violaciones lo demuestra {@link ArchitectureRulesNegativeTest}.
 */
@AnalyzeClasses(
    packages = "com.usac.jobseeker",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String ROOT = "com.usac.jobseeker";

  @ArchTest static final ArchRule ARCH_01 = ArchitectureRules.arch01(ROOT);
  @ArchTest static final ArchRule ARCH_02 = ArchitectureRules.arch02(ROOT);
  @ArchTest static final ArchRule ARCH_03 = ArchitectureRules.arch03(ROOT);
  @ArchTest static final ArchRule ARCH_04 = ArchitectureRules.arch04(ROOT);
  @ArchTest static final ArchRule ARCH_05 = ArchitectureRules.arch05(ROOT);
  @ArchTest static final ArchRule ARCH_06 = ArchitectureRules.arch06(ROOT);
  @ArchTest static final ArchRule ARCH_07 = ArchitectureRules.arch07(ROOT);
  @ArchTest static final ArchRule ARCH_08 = ArchitectureRules.arch08(ROOT);
  @ArchTest static final ArchRule ARCH_09 = ArchitectureRules.arch09(ROOT);
  @ArchTest static final ArchRule ARCH_10 = ArchitectureRules.arch10(ROOT);
  @ArchTest static final ArchRule ARCH_11 = ArchitectureRules.arch11(ROOT);
  @ArchTest static final ArchRule ARCH_12 = ArchitectureRules.arch12(ROOT);
  @ArchTest static final ArchRule ARCH_13 = ArchitectureRules.arch13(ROOT);
  @ArchTest static final ArchRule ARCH_14 = ArchitectureRules.arch14(ROOT);
}
