package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import java.util.List;

/** Puerto de salida de la etapa 1 del pipeline de sugeridos (ADR-0017, decisión 3). */
public interface CandidateRetrievalPort {

  /**
   * Los `limit` perfiles **descubribles** más cercanos al vector, solo entre los calculados con
   * `model` (un vector de otro modelo no es comparable), ordenados del más al menos parecido. Un
   * perfil no descubrible o sin vector, o anonimizado, no aparece nunca (BR-VIS-04, BR-DATA-05).
   */
  List<NearestProfile> nearestDiscoverable(EmbeddingVector query, String model, int limit);
}
