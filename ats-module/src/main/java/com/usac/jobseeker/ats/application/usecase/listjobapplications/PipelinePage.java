package com.usac.jobseeker.ats.application.usecase.listjobapplications;

import java.util.List;

/**
 * Una página del pipeline (`api-contract-overview.md` §8).
 *
 * @param totalElements las que cumplen el filtro, no solo las de esta página
 * @param truncated la vacante tiene más postulaciones que el tope de la regla 6: solo se ordenaron
 *     las primeras, y es el disparador de la proyección de D4
 */
public record PipelinePage(
    List<PipelineItem> content, int page, int size, long totalElements, boolean truncated) {

  public PipelinePage {
    content = List.copyOf(content);
  }
}
