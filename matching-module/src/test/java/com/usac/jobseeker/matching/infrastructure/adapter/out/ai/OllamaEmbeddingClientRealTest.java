package com.usac.jobseeker.matching.infrastructure.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingRejectedException;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingUnavailableException;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * El contrato del cliente contra un **Ollama real** (TD-029;
 * `08-medicion-ollama-y-bakeoff-embeddings.md` §7, punto 5). Las pruebas de
 * `OllamaEmbeddingClientTest` usan un servidor de prueba y no pueden saber qué responde Ollama de
 * verdad: aquí se comprueba lo que el ADR-0018 supone y el servidor de prueba solo imita.
 *
 * <p>No corre por defecto: necesita un Ollama con el modelo ya descargado. Se activa con la
 * variable `OLLAMA_REAL_URL` (y `OLLAMA_REAL_MODEL`, por defecto el provisional del ADR-0018):
 *
 * <pre>
 * OLLAMA_REAL_URL=http://127.0.0.1:11434 mvn -pl matching-module test -Dtest=OllamaEmbeddingClientRealTest
 * </pre>
 *
 * <p>Solo valida **contratos**, nunca tiempos: la latencia depende del hardware (una máquina sin
 * AVX2 es de decenas a cientos de veces más lenta) y se mide aparte, con el procedimiento del
 * documento 08. Los tiempos de espera son generosos por eso.
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_REAL_URL", matches = ".+")
class OllamaEmbeddingClientRealTest {

  private static final String URL = System.getenv("OLLAMA_REAL_URL");
  private static final String MODEL =
      System.getenv().getOrDefault("OLLAMA_REAL_MODEL", "qwen3-embedding:0.6b");
  private static final int DIMENSIONS = 1024;
  private static final int NUM_CTX = 8192;
  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private static OllamaEmbeddingClient client(String baseUrl, int dimensions, int numCtx) {
    return new OllamaEmbeddingClient(
        HttpClient.newHttpClient(), URI.create(baseUrl), MODEL, dimensions, numCtx, "30m", TIMEOUT);
  }

  private static float[] embed(String text) {
    return client(URL, DIMENSIONS, NUM_CTX).embed(EmbeddableText.of(text)).vector().values();
  }

  private static double norm(float[] v) {
    double sum = 0;
    for (float x : v) {
      sum += (double) x * x;
    }
    return Math.sqrt(sum);
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
    }
    return dot / (norm(a) * norm(b));
  }

  @Test
  void theModelAnswersWithTheColumnDimensionAndANormalizedVector() {
    var embedded =
        client(URL, DIMENSIONS, NUM_CTX).embed(EmbeddableText.of("Ingeniero de software"));

    assertThat(embedded.model()).isEqualTo(MODEL);
    assertThat(embedded.vector().values()).hasSize(DIMENSIONS);
    // Vectores unitarios: el coseno equivale al producto punto, que es lo que usa el índice HNSW.
    assertThat(norm(embedded.vector().values()))
        .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
    assertThat(embedded.truncated()).isFalse();
  }

  // BR-MATCH-03: el mismo texto da el mismo vector, o el hash dejaría de significar «no cambió».
  @Test
  void theSameTextGivesTheSameVector() {
    String text = "Desarrollador backend con Java y PostgreSQL";

    assertThat(cosine(embed(text), embed(text))).isGreaterThan(0.9999);
  }

  // ADR-0018: un texto que no cabe es un error, no un vector de una versión recortada en silencio.
  @Test
  void aTextThatDoesNotFitInTheContextIsRejectedAndNeverTruncated() {
    var small = client(URL, DIMENSIONS, 128);
    var longText =
        EmbeddableText.of("desarrollo de servicios backend con java y spring boot ".repeat(40));

    assertThatThrownBy(() -> small.embed(longText))
        .isInstanceOf(EmbeddingRejectedException.class)
        // Ni el mensaje ni la excepción citan el texto del perfil (BR-DATA-01).
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("desarrollo de servicios"));
  }

  // Si la columna es de otra dimensión que el modelo, se rechaza sin guardar nada.
  @Test
  void aVectorOfAnotherDimensionThanTheColumnIsRejected() {
    var wrongColumn = client(URL, 512, NUM_CTX);
    var text = EmbeddableText.of("Cualquier texto");

    assertThatThrownBy(() -> wrongColumn.embed(text))
        .isInstanceOf(EmbeddingRejectedException.class);
  }

  @Test
  void anUnreachableServerIsUnavailableNotRejectedSoTheQueueRetries() {
    var down = client("http://127.0.0.1:1", DIMENSIONS, NUM_CTX);
    var text = EmbeddableText.of("Cualquier texto");

    assertThatThrownBy(() -> down.embed(text)).isInstanceOf(EmbeddingUnavailableException.class);
  }

  // Sanidad semántica mínima; no sustituye el protocolo de 300 pares del ADR-0018.
  @Test
  void relatedTextsAreCloserThanUnrelatedOnes() {
    float[] profile = embed("Desarrollador backend con Java, Spring Boot y PostgreSQL; APIs REST.");
    float[] posting =
        embed("Vacante: ingeniero backend Java con Spring Boot y bases de datos relacionales.");
    float[] nurse =
        embed(
            "Enfermera con experiencia en cuidados intensivos pediátricos y turnos hospitalarios.");

    assertThat(cosine(profile, posting)).isGreaterThan(cosine(profile, nurse) + 0.2);
  }
}
