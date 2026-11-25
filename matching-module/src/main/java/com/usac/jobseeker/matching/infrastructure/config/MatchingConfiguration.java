package com.usac.jobseeker.matching.infrastructure.config;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedJobService;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedJobUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedProfileService;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedProfileUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingClientPort;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingFreshnessPort;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingQueuePolicy;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleJobEventService;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleJobEventUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleProfileEventService;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleProfileEventUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.ProcessEmbeddingQueueService;
import com.usac.jobseeker.matching.application.usecase.embedentity.ProcessEmbeddingQueueUseCase;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.OwnedJobPort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.RecruiterTrustPort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobService;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobUseCase;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsPolicy;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsSources;
import com.usac.jobseeker.matching.infrastructure.adapter.in.event.JobEventsConsumer;
import com.usac.jobseeker.matching.infrastructure.adapter.in.event.ProfileEventsConsumer;
import com.usac.jobseeker.matching.infrastructure.adapter.in.job.EmbeddingWorker;
import com.usac.jobseeker.matching.infrastructure.adapter.out.ai.OllamaEmbeddingClient;
import com.usac.jobseeker.matching.infrastructure.adapter.out.metrics.EmbeddingQueueMetrics;
import com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcEmbeddingRepository;
import com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcEmbeddingWorkQueue;
import com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcProfileViewLog;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Raíz de composición de `matching` (ADR-0015, ARCH-02): los casos de uso son beans aquí y no
 * llevan estereotipos en `application/`. El cliente de Ollama se construye con la configuración,
 * sin abrir ninguna conexión al arrancar: la aplicación debe poder iniciar con Ollama apagado y la
 * cola acumula hasta que vuelva (NFR-AVAIL-02).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingConfiguration {

  @Bean
  JdbcEmbeddingRepository jdbcEmbeddingRepository(JdbcClient jdbc) {
    return new JdbcEmbeddingRepository(jdbc);
  }

  @Bean
  JdbcEmbeddingWorkQueue jdbcEmbeddingWorkQueue(JdbcClient jdbc) {
    return new JdbcEmbeddingWorkQueue(jdbc);
  }

  /**
   * Es a la vez el puerto de frescura y el enlazador de medidores: Spring Boot enlaza todo
   * `MeterBinder`.
   */
  @Bean
  EmbeddingQueueMetrics embeddingQueueMetrics(JdbcClient jdbc) {
    return new EmbeddingQueueMetrics(jdbc);
  }

  @Bean
  EmbeddingClientPort embeddingClient(MatchingProperties properties) {
    MatchingProperties.Embedding e = properties.embedding();
    HttpClient http =
        HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    return new OllamaEmbeddingClient(
        http,
        URI.create("http://" + e.host() + ":" + e.port()),
        e.model(),
        e.dimensions(),
        e.numContext(),
        e.keepAlive(),
        e.timeout());
  }

  @Bean
  EmbedProfileUseCase embedProfileUseCase(
      CandidateSignalsReader reader,
      EmbeddingClientPort client,
      JdbcEmbeddingRepository store,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    return new EmbedProfileService(
        reader, client, store, metrics, Clock.systemUTC(), properties.embedding().maxCharacters());
  }

  @Bean
  EmbedJobUseCase embedJobUseCase(
      JobSignalsReader reader,
      EmbeddingClientPort client,
      JdbcEmbeddingRepository store,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    return new EmbedJobService(
        reader, client, store, metrics, Clock.systemUTC(), properties.embedding().maxCharacters());
  }

  @Bean
  HandleJobEventUseCase handleJobEventUseCase(
      JdbcEmbeddingWorkQueue queue, UseCaseMetricsPort metrics, MatchingProperties properties) {
    return new HandleJobEventService(
        queue, metrics, Clock.systemUTC(), properties.queue().window());
  }

  @Bean
  HandleProfileEventUseCase handleProfileEventUseCase(
      JdbcEmbeddingWorkQueue queue,
      JdbcEmbeddingRepository store,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    return new HandleProfileEventService(
        queue, store, metrics, Clock.systemUTC(), properties.queue().window());
  }

  @Bean
  ProcessEmbeddingQueueUseCase processEmbeddingQueueUseCase(
      JdbcEmbeddingWorkQueue queue,
      EmbedProfileUseCase embedProfile,
      EmbedJobUseCase embedJob,
      UseCaseMetricsPort metrics,
      EmbeddingFreshnessPort freshness,
      MatchingProperties properties) {
    MatchingProperties.Queue q = properties.queue();
    return new ProcessEmbeddingQueueService(
        queue,
        embedProfile,
        embedJob,
        new EmbeddingQueuePolicy(
            q.batchSize(), q.lease(), q.maxAttempts(), q.baseBackoff(), q.maxBackoff()),
        metrics,
        freshness,
        Clock.systemUTC());
  }

  /**
   * El sondeador de la bandeja descubre todos los `OutboxEventConsumer` (ADR-0016, decisión 12).
   */
  @Bean
  OutboxEventConsumer profileEventsConsumer(HandleProfileEventUseCase handler) {
    return new ProfileEventsConsumer(handler);
  }

  @Bean
  OutboxEventConsumer jobEventsConsumer(HandleJobEventUseCase handler) {
    return new JobEventsConsumer(handler);
  }

  @Bean
  JdbcProfileViewLog jdbcProfileViewLog(JdbcClient jdbc) {
    return new JdbcProfileViewLog(jdbc);
  }

  @Bean
  SuggestCandidatesForJobUseCase suggestCandidatesForJobUseCase(
      RecruiterTrustPort trust,
      OwnedJobPort ownedJobs,
      JdbcEmbeddingRepository embeddings,
      JdbcProfileViewLog views,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    MatchingProperties.Suggestions s = properties.suggestions();
    return new SuggestCandidatesForJobService(
        trust,
        new SuggestionsSources(ownedJobs, embeddings, embeddings),
        views,
        new SuggestionsPolicy(s.viewQuota(), s.window(), s.maxPageSize()),
        metrics,
        Clock.systemUTC());
  }

  @Bean
  EmbeddingWorker embeddingWorker(ProcessEmbeddingQueueUseCase queue) {
    return new EmbeddingWorker(queue);
  }
}
