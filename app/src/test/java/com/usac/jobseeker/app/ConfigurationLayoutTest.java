package com.usac.jobseeker.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Los perfiles dev y prod viven solo en `app`: en un único classpath, dos módulos con el mismo
 * `application-dev.yml` chocan y Spring carga uno solo, en silencio. Esta prueba falla si un módulo
 * vuelve a aportar un archivo con ese nombre.
 */
class ConfigurationLayoutTest {

  private static final Set<String> PROFILE_FILES =
      Set.of("application-dev.yml", "application-prod.yml");

  @Test
  void profileFilesExistOnlyOnceOnTheClasspath() throws IOException {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    for (String name : PROFILE_FILES) {
      List<java.net.URL> found = Collections.list(loader.getResources(name));
      assertThat(found).as("%s en el classpath", name).hasSize(1);
    }
  }

  @Test
  void everyModuleConfigurationHasAUniqueName() throws IOException {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    for (String module : List.of("auth", "cv", "job", "matching", "ats", "tracker")) {
      String name = "application-" + module + ".yml";
      assertThat(Collections.list(loader.getResources(name)))
          .as("%s en el classpath", name)
          .hasSize(1);
    }
  }
}
