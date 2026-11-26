package com.usac.jobseeker.matching.domain.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grupos de nombres que son **la misma habilidad** escrita de otro modo («JS» y «JavaScript», «k8s»
 * y «Kubernetes», «ML» y «aprendizaje automático»). Cada nombre ya llega normalizado (sin
 * mayúsculas, acentos ni puntuación, ver {@link ScoreCatalogs#nameKey}). Es una **medida
 * provisional** hasta que exista la taxonomía de habilidades del ADR-0019 (TD-038): cuando `cv`
 * enlace cada habilidad a un concepto, estos grupos pasan a ser sus habilidades locales sembradas
 * (decisión 2 de ese ADR) y esta clase desaparece.
 *
 * <p>Solo entran equivalencias **inequívocas**: «Spring» y «Spring Boot», «Angular» y «AngularJS»,
 * o «Java» y «JavaScript» son cosas distintas y no se unen; un falso positivo infla un puntaje y es
 * peor que una coincidencia que no se detecta. El primer nombre de cada grupo es el canónico.
 */
final class SkillAliases {

  private static final List<List<String>> ALIAS_GROUPS =
      List.of(
          List.of("javascript", "js", "ecmascript"),
          List.of("typescript", "ts"),
          List.of("nodejs", "node"),
          List.of("react", "reactjs"),
          List.of("vue", "vuejs"),
          List.of("postgresql", "postgres", "psql", "pgsql"),
          List.of("mongodb", "mongo"),
          List.of("kubernetes", "k8s"),
          List.of("c#", "csharp"),
          List.of("c++", "cpp", "cplusplus"),
          List.of("dotnet", "net"),
          List.of("golang", "go"),
          List.of("python", "python3"),
          List.of("html", "html5"),
          List.of("css", "css3"),
          List.of("amazonwebservices", "aws"),
          List.of("googlecloudplatform", "gcp", "googlecloud"),
          List.of("microsoftazure", "azure"),
          List.of("sqlserver", "mssql", "microsoftsqlserver"),
          List.of("elasticsearch", "elastic"),
          List.of("graphql", "gql"),
          List.of("restapi", "rest", "restful", "apirest", "apirestful"),
          List.of("cicd", "integracioncontinua", "continuousintegration"),
          List.of("linux", "gnulinux"),
          List.of("objectivec", "objc"),
          List.of("excel", "msexcel", "microsoftexcel"),
          List.of("powerbi", "microsoftpowerbi"),
          List.of("machinelearning", "ml", "aprendizajeautomatico"),
          List.of("deeplearning", "aprendizajeprofundo"),
          List.of("artificialintelligence", "ai", "ia", "inteligenciaartificial"),
          List.of("naturallanguageprocessing", "nlp", "procesamientodelenguajenatural"),
          List.of("unittesting", "pruebasunitarias", "testingunitario"),
          List.of("communication", "comunicacion"),
          List.of("teamwork", "trabajoenequipo"),
          List.of("leadership", "liderazgo"),
          List.of("problemsolving", "resoluciondeproblemas"));

  private static final Map<String, String> CANONICAL_OF = build();

  private SkillAliases() {}

  /** El nombre canónico del grupo al que pertenece, o el mismo nombre si no tiene alias. */
  static String canonical(String normalizedName) {
    return CANONICAL_OF.getOrDefault(normalizedName, normalizedName);
  }

  /** Los grupos, para que una prueba verifique que ningún nombre está en dos. */
  static List<List<String>> groups() {
    return ALIAS_GROUPS;
  }

  // Falla al cargar la clase, no en la primera comparación: un nombre en dos grupos haría que dos
  // habilidades distintas se confundieran según el orden en que se lean.
  private static Map<String, String> build() {
    Map<String, String> canonical = new HashMap<>();
    for (List<String> group : ALIAS_GROUPS) {
      for (String name : group) {
        if (canonical.put(name, group.get(0)) != null) {
          throw new IllegalStateException("el nombre «" + name + "» está en dos grupos de alias");
        }
      }
    }
    return Map.copyOf(canonical);
  }
}
