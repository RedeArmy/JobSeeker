package com.usac.jobseeker.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorLookupPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfileCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.AvailabilityExclusionLookupPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingWorkQueuePort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.AvailabilityPort;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * TD-039: las lecturas por lotes de los puertos tienen una implementación **por defecto** que
 * pregunta de a uno, correcta pero lenta, para no romper a quien simula el puerto en una prueba. El
 * riesgo es que un adaptador **real** olvide sustituirla y vuelva, sin que nada falle, a una
 * consulta por cuenta. Esta prueba exige que cada adaptador de producción que implementa uno de
 * estos puertos declare su propia versión de cada método por defecto.
 */
class BatchReadsAreOverriddenTest {

  private static final List<Class<?>> PORTS =
      List.of(
          ActorLookupPort.class,
          CandidateCapabilityPort.class,
          ProfessionalProfileReadPort.class,
          CandidateProfileReadPort.class,
          ProfileCollectionsReadPort.class,
          CandidateCollectionsReadPort.class,
          AvailabilityExclusionLookupPort.class,
          CandidateSignalsReader.class,
          AvailabilityPort.class,
          EmbeddingWorkQueuePort.class);

  private static Set<String> defaultMethods(Class<?> port) {
    return Arrays.stream(port.getDeclaredMethods())
        .filter(Method::isDefault)
        .map(Method::getName)
        .collect(Collectors.toSet());
  }

  private static Set<String> declared(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .map(Method::getName)
        .collect(Collectors.toSet());
  }

  @Test
  void everyProductionAdapterOfAPortWithDefaultBatchMethodsOverridesThem() throws Exception {
    var production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.usac.jobseeker");
    List<String> missing = new ArrayList<>();
    int checked = 0;
    for (Class<?> port : PORTS) {
      Set<String> defaults = defaultMethods(port);
      assertThat(defaults)
          .as("el puerto %s debe tener métodos por defecto", port.getSimpleName())
          .isNotEmpty();
      for (JavaClass candidate : production) {
        boolean adapter =
            candidate.getPackageName().contains(".infrastructure.adapter.")
                && !candidate.isInterface()
                && candidate.getAllRawInterfaces().stream()
                    .anyMatch(i -> i.getName().equals(port.getName()));
        if (!adapter) {
          continue;
        }
        checked++;
        Set<String> own = declared(Class.forName(candidate.getName()));
        for (String method : defaults) {
          if (!own.contains(method)) {
            missing.add(
                candidate.getSimpleName() + " no sustituye " + port.getSimpleName() + "." + method);
          }
        }
      }
    }

    assertThat(checked)
        .as("adaptadores de producción revisados")
        .isGreaterThanOrEqualTo(PORTS.size());
    assertThat(missing).isEmpty();
  }
}
