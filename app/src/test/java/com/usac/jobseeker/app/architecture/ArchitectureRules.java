package com.usac.jobseeker.app.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameStartingWith;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.metaAnnotatedWith;
import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reglas ARCH-01..10 del ADR-0015, parametrizadas por el paquete raíz para poder aplicarlas al
 * código real y a clases de ejemplo que las violan a propósito (sin eso, con los módulos vacíos
 * pasarían sin demostrar que detectan algo).
 *
 * <p>Todas llevan {@code allowEmptyShould(true)}: mientras un módulo no tiene clases, una
 * coincidencia vacía no debe fallar la prueba (ADR-0015, decisión 3).
 */
final class ArchitectureRules {

  /** Módulos de negocio; `common` y `app` no cuentan como módulo a efectos de ARCH-04. */
  static final List<String> BUSINESS_MODULES =
      List.of("auth", "cv", "job", "matching", "ats", "tracker");

  private ArchitectureRules() {}

  /** Identificador de regla → regla, en orden, para el paquete raíz indicado. */
  static Map<String, ArchRule> all(String root) {
    Map<String, ArchRule> rules = new LinkedHashMap<>();
    rules.put("ARCH-01", arch01(root));
    rules.put("ARCH-02", arch02(root));
    rules.put("ARCH-03", arch03(root));
    rules.put("ARCH-04", arch04(root));
    rules.put("ARCH-05", arch05(root));
    rules.put("ARCH-06", arch06(root));
    rules.put("ARCH-07", arch07(root));
    rules.put("ARCH-08", arch08(root));
    rules.put("ARCH-09", arch09(root));
    rules.put("ARCH-10", arch10(root));
    return rules;
  }

  /** ARCH-01: el dominio no depende de frameworks. */
  static ArchRule arch01(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + "..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "jakarta.servlet..",
            "com.fasterxml.jackson..",
            "tools.jackson..",
            "io.micrometer..")
        .as("ARCH-01: domain no depende de Spring, JPA, servlet, Jackson ni Micrometer")
        .allowEmptyShould(true);
  }

  /**
   * ARCH-02: application no depende de infrastructure ni de web, datos, JDBC, seguridad o JPA. El
   * único Spring permitido es org.springframework.transaction.. porque el caso de uso es la
   * frontera transaccional (ADR-0016).
   */
  static ArchRule arch02(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + "..application..")
        .should()
        .dependOnClassesThat(
            resideInAPackage(root + "..infrastructure..")
                .or(resideInAPackage("jakarta.persistence.."))
                .or(resideInAPackage("jakarta.servlet.."))
                .or(
                    resideInAPackage("org.springframework..")
                        .and(not(resideInAPackage("org.springframework.transaction.."))))
                .as("infrastructure, JPA, servlet o Spring distinto de transaction"))
        .as("ARCH-02: application solo usa Spring de transacciones y no depende de infrastructure")
        .allowEmptyShould(true);
  }

  /** ARCH-03: la dependencia va de fuera hacia dentro, nunca al revés. */
  static ArchRule arch03(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + "..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(root + "..application..", root + "..infrastructure..")
        .as("ARCH-03: domain no depende de application ni de infrastructure")
        .allowEmptyShould(true);
  }

  /**
   * ARCH-04: la superficie pública de un módulo son sus interfaces de caso de uso con su
   * Command/Result y sus eventos. Solo {@code infrastructure.adapter} importa otro módulo, y solo
   * esos tipos.
   */
  static ArchRule arch04(String root) {
    return classes()
        .that()
        .resideInAPackage(root + "..")
        .should(onlyUseOtherModulesThroughTheirPublicSurface(root))
        .as(
            "ARCH-04: un módulo solo usa de otro sus casos de uso y eventos, y solo desde"
                + " infrastructure.adapter")
        .allowEmptyShould(true);
  }

  /** ARCH-05: sin ciclos entre módulos y ningún módulo de negocio ni common depende de app. */
  static ArchRule arch05(String root) {
    ArchRule noCycles =
        SlicesRuleDefinition.slices()
            .matching(root + ".(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true);
    ArchRule noDependencyOnApp =
        noClasses()
            .that()
            .resideInAnyPackage(modulePackages(root, true))
            .should()
            .dependOnClassesThat()
            .resideInAPackage(root + ".app..")
            .allowEmptyShould(true);
    return com.tngtech.archunit.lang.CompositeArchRule.of(noCycles)
        .and(noDependencyOnApp)
        .as("ARCH-05: sin ciclos entre módulos y nadie depende de app")
        .allowEmptyShould(true);
  }

  /** ARCH-06: cv y job no dependen de matching, ats ni tracker (domain-model.md §5). */
  static ArchRule arch06(String root) {
    return noClasses()
        .that()
        .resideInAnyPackage(root + ".cv..", root + ".job..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(root + ".matching..", root + ".ats..", root + ".tracker..")
        .as("ARCH-06: cv y job no dependen de matching, ats ni tracker")
        .allowEmptyShould(true);
  }

  /** ARCH-07: las entidades JPA solo viven en infrastructure.adapter.out.persistence. */
  static ArchRule arch07(String root) {
    return classes()
        .that()
        .resideInAPackage(root + "..")
        .and()
        .areAnnotatedWith("jakarta.persistence.Entity")
        .should()
        .resideInAPackage(root + "..infrastructure.adapter.out.persistence..")
        .as("ARCH-07: @Entity solo en infrastructure.adapter.out.persistence")
        .allowEmptyShould(true);
  }

  /** ARCH-08: los controladores solo viven en infrastructure.adapter.in.web. */
  static ArchRule arch08(String root) {
    return classes()
        .that(
            resideInAPackage(root + "..")
                .and(
                    annotatedWith("org.springframework.stereotype.Controller")
                        .or(metaAnnotatedWith("org.springframework.stereotype.Controller")))
                .as("controladores"))
        .should()
        .resideInAPackage(root + "..infrastructure.adapter.in.web..")
        .as("ARCH-08: @Controller y @RestController solo en infrastructure.adapter.in.web")
        .allowEmptyShould(true);
  }

  /** ARCH-09: todo @Scheduled reside en infrastructure. */
  static ArchRule arch09(String root) {
    return methods()
        .that()
        .areAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
        .and()
        .areDeclaredInClassesThat()
        .resideInAPackage(root + "..")
        .should()
        .beDeclaredInClassesThat()
        .resideInAPackage(root + "..infrastructure..")
        .as("ARCH-09: @Scheduled solo en infrastructure")
        .allowEmptyShould(true);
  }

  /**
   * ARCH-10: ningún efecto con escritura en base de datos corre en afterCommit (ADR-0016; lección
   * de TD-SEC-102 de Clavaris).
   */
  static ArchRule arch10(String root) {
    return noClasses()
        .that()
        .resideInAnyPackage(root + "..application..", root + "..domain..")
        .should()
        .dependOnClassesThat(simpleNameStartingWith("TransactionSynchronization"))
        .as("ARCH-10: nada de TransactionSynchronization* en application ni domain")
        .allowEmptyShould(true);
  }

  private static String[] modulePackages(String root, boolean includeCommon) {
    java.util.List<String> packages = new java.util.ArrayList<>();
    for (String module : BUSINESS_MODULES) {
      packages.add(root + "." + module + "..");
    }
    if (includeCommon) {
      packages.add(root + ".common..");
    }
    return packages.toArray(String[]::new);
  }

  /** Módulo de negocio al que pertenece una clase, o null si no es de ninguno. */
  private static String moduleOf(JavaClass javaClass, String root) {
    String prefix = root + ".";
    String packageName = javaClass.getPackageName();
    if (!packageName.startsWith(prefix)) {
      return null;
    }
    String rest = packageName.substring(prefix.length());
    String first = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
    return BUSINESS_MODULES.contains(first) ? first : null;
  }

  private static ArchCondition<JavaClass> onlyUseOtherModulesThroughTheirPublicSurface(
      String root) {
    return new ArchCondition<>("usar otro módulo solo por sus casos de uso y eventos") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        String originModule = moduleOf(origin, root);
        if (originModule == null) {
          return;
        }
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          String targetModule = moduleOf(target, root);
          if (targetModule == null || targetModule.equals(originModule)) {
            continue;
          }
          boolean fromAdapter = origin.getPackageName().contains(".infrastructure.adapter");
          boolean toSurface =
              target.getPackageName().contains(".application.usecase")
                  || target.getPackageName().contains(".domain.event");
          if (!fromAdapter || !toSurface) {
            events.add(violated(dependency, dependency.getDescription()));
          }
        }
      }
    };
  }
}
