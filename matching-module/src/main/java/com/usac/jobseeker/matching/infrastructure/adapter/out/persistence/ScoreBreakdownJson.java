package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.matching.domain.model.DataStatus;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.DimensionScore;
import com.usac.jobseeker.matching.domain.model.ScoreBreakdown;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * El desglose de un puntaje como JSON, para guardarlo y leerlo sin perder nada. Un desglose
 * anonimizado queda como `{}` (BR-DATA-05: lo derivado del perfil se vacía y el par, el puntaje y
 * la versión se conservan), y se lee como un desglose sin dimensiones.
 */
final class ScoreBreakdownJson {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private ScoreBreakdownJson() {}

  static String write(ScoreBreakdown breakdown) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("structuredPresent", breakdown.structuredPresent());
    root.put("structured", breakdown.structured());
    root.put("semantic", breakdown.semantic());
    root.put("alpha", breakdown.alpha());
    root.put("completeness", breakdown.completeness());
    ArrayNode dimensions = root.putArray("dimensions");
    for (DimensionScore d : breakdown.dimensions()) {
      ObjectNode node = dimensions.addObject();
      node.put("dimension", d.dimension().name());
      node.put("status", d.status().name());
      node.put("score", d.score());
      node.put("weight", d.weight());
      node.put("explanation", d.explanation());
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException failure) {
      throw new IllegalStateException("no se pudo serializar el desglose", failure);
    }
  }

  /** `completeness` es el de la fila: un desglose anonimizado ya no lo trae. */
  static ScoreBreakdown read(String json, double completeness) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JacksonException failure) {
      throw new IllegalStateException("el desglose guardado no es un JSON válido", failure);
    }
    if (root == null || !root.has("dimensions")) {
      return new ScoreBreakdown(List.of(), false, 0, 0, 1, completeness);
    }
    List<DimensionScore> dimensions = new ArrayList<>();
    for (JsonNode node : root.get("dimensions")) {
      dimensions.add(
          new DimensionScore(
              Dimension.valueOf(node.get("dimension").asText()),
              DataStatus.valueOf(node.get("status").asText()),
              node.get("score").asDouble(),
              node.get("weight").asDouble(),
              node.get("explanation").asText()));
    }
    return new ScoreBreakdown(
        dimensions,
        root.get("structuredPresent").asBoolean(),
        root.get("structured").asDouble(),
        root.get("semantic").asDouble(),
        root.get("alpha").asDouble(),
        root.get("completeness").asDouble());
  }
}
