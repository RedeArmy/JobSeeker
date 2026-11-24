package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;

/**
 * Puerto de salida hacia el modelo de embeddings (Ollama, ADR-0018). Recibe un {@link
 * EmbeddableText} y no un `String`: el único texto que puede salir hacia un tercero es el ya
 * redactado, y el compilador lo exige (TD-021). Quien implemente este puerto no puede recibir texto
 * crudo aunque quiera.
 */
public interface EmbeddingClientPort {

  /** El vector del texto, el modelo que lo produjo y si el texto se truncó. */
  record Embedded(EmbeddingVector vector, String model, boolean truncated) {}

  Embedded embed(EmbeddableText text);
}
