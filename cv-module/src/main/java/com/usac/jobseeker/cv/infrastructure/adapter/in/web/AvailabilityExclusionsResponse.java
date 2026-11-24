package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ExclusionView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** La lista de exclusión de disponibilidad del candidato. */
@Schema(description = "Las cuentas a las que el candidato oculta su estado de disponibilidad.")
public record AvailabilityExclusionsResponse(List<Item> exclusions) {

  @Schema(description = "Una cuenta excluida.")
  public record Item(
      @Schema(description = "Identificador de la cuenta excluida.") String accountId,
      @Schema(
              description = "Cuándo se añadió, en UTC (ISO-8601).",
              example = "2026-11-24T16:30:00Z")
          String createdAt) {}

  static AvailabilityExclusionsResponse from(List<ExclusionView> views) {
    return new AvailabilityExclusionsResponse(
        views.stream()
            .map(v -> new Item(v.accountId().toString(), v.createdAt().toString()))
            .toList());
  }
}
