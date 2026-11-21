package com.usac.jobseeker.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final ArchRule DOMAIN_FREE_OF_FRAMEWORKS =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..");

  @Test
  void realClassesRespectTheDomainRule() {
    JavaClasses classes =
        new ClassFileImporter().importPackages("com.usac.jobseeker.cv", "com.usac.jobseeker.app");
    DOMAIN_FREE_OF_FRAMEWORKS.check(classes);
  }

  @Test
  void aDomainClassImportingSpringIsRejected() {
    JavaClasses bad = new ClassFileImporter().importPackages("com.usac.jobseeker.fixture.bad");
    assertThatThrownBy(() -> DOMAIN_FREE_OF_FRAMEWORKS.check(bad))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("was violated");
  }
}
