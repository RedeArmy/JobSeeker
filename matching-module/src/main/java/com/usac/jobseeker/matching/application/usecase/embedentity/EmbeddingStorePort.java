package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.Optional;

/**
 * Puerto de salida de los vectores, que son de `matching` (`data-model.md` §4.7.1). Guarda además
 * la **copia** de `discoverable` (perfil) y de «publicada» (vacante) que necesitan los índices
 * parciales.
 *
 * <p>Las copias siguen una regla simple: el **evento posterior gana**. Fijar la copia con {@code
 * setProfileDiscoverable} crea la fila si falta, de modo que un evento que llega antes del primer
 * cálculo no se pierde; y guardar un vector solo fija la copia al *crear* la fila, nunca la pisa,
 * para que un cálculo lento no revierta una descubribilidad que el usuario acaba de apagar.
 */
public interface EmbeddingStorePort {

  /** El estado del perfil sin leer el vector, o vacío si `matching` no sabe nada de él. */
  Optional<ProfileEmbeddingState> findProfileState(CandidateProfileId id);

  /** El vector del perfil con todos sus metadatos, o vacío si aún no tiene. */
  Optional<EntityEmbedding> findProfileEmbedding(CandidateProfileId id);

  /**
   * Guarda el vector del perfil. Si la fila no existe se crea con `discoverableIfNew`; si existe,
   * la copia **no** cambia.
   */
  void saveProfileEmbedding(
      CandidateProfileId id, boolean discoverableIfNew, EntityEmbedding embedding);

  /** Fija la copia de descubribilidad; crea la fila sin vector si no existía. */
  void setProfileDiscoverable(CandidateProfileId id, boolean discoverable);

  /**
   * Anonimización (BR-DATA-05): anula el bloque del vector y fuerza la descubribilidad a falso. No
   * borra la fila, para que repetir el evento sea idempotente. Devuelve si había fila.
   */
  boolean clearProfileEmbedding(CandidateProfileId id);

  Optional<JobEmbeddingState> findJobState(JobPostingId id);

  Optional<EntityEmbedding> findJobEmbedding(JobPostingId id);

  /** Igual que el del perfil: crea la fila con `publishedIfNew` o deja la copia como está. */
  void saveJobEmbedding(JobPostingId id, boolean publishedIfNew, EntityEmbedding embedding);

  /** Fija la copia de «publicada»; crea la fila sin vector si no existía. */
  void setJobPublished(JobPostingId id, boolean published);
}
