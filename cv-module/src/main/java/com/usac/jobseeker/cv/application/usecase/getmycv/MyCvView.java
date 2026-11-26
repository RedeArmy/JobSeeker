package com.usac.jobseeker.cv.application.usecase.getmycv;

import java.util.List;
import java.util.Optional;

/**
 * El CV vigente y todas las versiones guardadas, de la más reciente a la más antigua.
 *
 * @param versions incluye la vigente; el tope por cuenta está en la configuración
 */
public record MyCvView(Optional<CvFileView> current, List<CvFileView> versions, int maxVersions) {

  public MyCvView {
    versions = List.copyOf(versions);
  }
}
