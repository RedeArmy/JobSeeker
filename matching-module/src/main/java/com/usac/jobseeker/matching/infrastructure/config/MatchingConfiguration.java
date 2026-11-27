package com.usac.jobseeker.matching.infrastructure.config;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreService;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.CalculateCompatibilityScoreUseCase;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoreCalculator;
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.ScoringSources;
import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedJobService;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedJobUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedProfileService;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbedProfileUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingClientPort;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingFreshnessPort;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingJobHandlers;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingQueuePolicy;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleJobEventService;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleJobEventUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleProfileEventService;
import com.usac.jobseeker.matching.application.usecase.embedentity.HandleProfileEventUseCase;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.ProcessEmbeddingQueueService;
import com.usac.jobseeker.matching.application.usecase.embedentity.ProcessEmbeddingQueueUseCase;
import com.usac.jobseeker.matching.application.usecase.explainscore.CandidateAccessPort;
import com.usac.jobseeker.matching.application.usecase.explainscore.CandidateJobAccess;
import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreService;
import com.usac.jobseeker.matching.application.usecase.explainscore.ExplainScoreUseCase;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreService;
import com.usac.jobseeker.matching.application.usecase.explainscore.WhatIfScoreUseCase;
import com.usac.jobseeker.matching.application.usecase.migratealgorithmversion.MigrateAlgorithmVersionService;
import com.usac.jobseeker.matching.application.usecase.migratealgorithmversion.MigrateAlgorithmVersionUseCase;
import com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews.ProfileViewRetentionPolicy;
import com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews.PurgeExpiredProfileViewsService;
import com.usac.jobseeker.matching.application.usecase.purgeexpiredprofileviews.PurgeExpiredProfileViewsUseCase;
import com.usac.jobseeker.matching.application.usecase.recomputescoresforentity.RecomputeScoresForEntityService;
import com.usac.jobseeker.matching.application.usecase.recomputescoresforentity.RecomputeScoresForEntityUseCase;
import com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile.SnapshotApplicationProfileService;
import com.usac.jobseeker.matching.application.usecase.snapshotapplicationprofile.SnapshotApplicationProfileUseCase;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.AvailabilityPort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.OwnedJobPort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.RecruiterTrustPort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.ScoringModel;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobService;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestCandidatesForJobUseCase;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsPolicy;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.SuggestionsSources;
import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.Dimension;
import com.usac.jobseeker.matching.domain.model.Weights;
import com.usac.jobseeker.matching.infrastructure.adapter.in.event.JobEventsConsumer;
import com.usac.jobseeker.matching.infrastructure.adapter.in.event.ProfileEventsConsumer;
import com.usac.jobseeker.matching.infrastructure.adapter.in.job.EmbeddingWorker;
import com.usac.jobseeker.matching.infrastructure.adapter.in.job.ProfileViewRetentionJob;
import com.usac.jobseeker.matching.infrastructure.adapter.in.job.ScoreMaintenanceWorker;
import com.usac.jobseeker.matching.infrastructure.adapter.out.ai.OllamaEmbeddingClient;
import com.usac.jobseeker.matching.infrastructure.adapter.out.metrics.EmbeddingQueueMetrics;
import com.usac.jobseeker.matching.infrastructure.adapter.out.metrics.ScoringCalibrationMetrics;
import com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcEmbeddingRepository;
import com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcEmbeddingWorkQueue;
import com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcProfileViewLog;
import com.usac.jobseeker.matching.infrastructure.adapter.out.persistence.JdbcScoreStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
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
  JdbcEmbeddingRepository jdbcEmbeddingRepository(JdbcClient jdbc, MatchingProperties properties) {
    return new JdbcEmbeddingRepository(jdbc, properties.retrieval().efSearch());
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
      JdbcScoreStore scores,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    return new HandleProfileEventService(
        queue, store, scores, metrics, Clock.systemUTC(), properties.queue().window());
  }

  @Bean
  ProcessEmbeddingQueueUseCase processEmbeddingQueueUseCase(
      JdbcEmbeddingWorkQueue queue,
      EmbedProfileUseCase embedProfile,
      EmbedJobUseCase embedJob,
      RecomputeScoresForEntityUseCase recompute,
      UseCaseMetricsPort metrics,
      EmbeddingFreshnessPort freshness,
      MatchingProperties properties) {
    MatchingProperties.Queue q = properties.queue();
    return new ProcessEmbeddingQueueService(
        queue,
        new EmbeddingJobHandlers(embedProfile, embedJob, recompute),
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
      CandidateSignalsReader candidates,
      JobSignalsReader jobs,
      AvailabilityPort availability,
      JdbcScoreStore scoreStore,
      JdbcProfileViewLog views,
      ScoringModel scoring,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    MatchingProperties.Suggestions s = properties.suggestions();
    return new SuggestCandidatesForJobService(
        trust,
        new SuggestionsSources(
            ownedJobs, embeddings, embeddings, candidates, jobs, availability, scoreStore),
        views,
        new SuggestionsPolicy(
            s.viewQuota(), s.window(), s.maxPageSize(), s.retrievalCap(), s.tieTolerance()),
        scoring,
        metrics,
        Clock.systemUTC());
  }

  // Los pesos y la calibración salen de configuración (BR-MATCH-05); sin parámetros de Platt el
  // puntaje queda marcado como no calibrado (ADR-0017, decisión 5).
  @Bean
  ScoringModel scoringModel(MatchingProperties properties) {
    MatchingProperties.Scoring scoring = properties.scoring();
    MatchingProperties.Weights w = scoring.weights();
    Map<Dimension, Double> weights = new EnumMap<>(Dimension.class);
    weights.put(Dimension.SKILLS, w.skills());
    weights.put(Dimension.EXPERIENCE, w.experience());
    weights.put(Dimension.EDUCATION, w.education());
    weights.put(Dimension.LOCATION_MODALITY, w.locationModality());
    weights.put(Dimension.SALARY, w.salary());
    weights.put(Dimension.LANGUAGES, w.languages());
    String model = properties.embedding().model();
    Calibration calibration =
        scoring.calibration().fitted()
            ? new Calibration(
                model, Calibration.Kind.PLATT, scoring.calibration().a(), scoring.calibration().b())
            : Calibration.uncalibrated(model);
    return new ScoringModel(
        new Weights(weights, scoring.alpha(), scoring.minCompleteness(), scoring.mandatoryFactor()),
        calibration);
  }

  @Bean
  ScoringCalibrationMetrics scoringCalibrationMetrics(ScoringModel scoring) {
    return new ScoringCalibrationMetrics(scoring);
  }

  @Bean
  EmbeddingWorker embeddingWorker(ProcessEmbeddingQueueUseCase queue) {
    return new EmbeddingWorker(queue);
  }

  // ---- puntaje guardado (TD-036, ADR-0031) ----

  @Bean
  JdbcScoreStore jdbcScoreStore(JdbcClient jdbc) {
    return new JdbcScoreStore(jdbc);
  }

  @Bean
  ScoreCalculator scoreCalculator(ScoringModel scoring) {
    return new ScoreCalculator(scoring, Clock.systemUTC());
  }

  @Bean
  ScoringSources scoringSources(
      CandidateSignalsReader candidates,
      JobSignalsReader jobs,
      JdbcEmbeddingRepository embeddings,
      JdbcScoreStore scoreStore) {
    return new ScoringSources(candidates, jobs, embeddings, embeddings, scoreStore);
  }

  @Bean
  CalculateCompatibilityScoreUseCase calculateCompatibilityScoreUseCase(
      ScoringSources sources, ScoreCalculator calculator, UseCaseMetricsPort metrics) {
    return new CalculateCompatibilityScoreService(sources, calculator, metrics, Clock.systemUTC());
  }

  @Bean
  RecomputeScoresForEntityUseCase recomputeScoresForEntityUseCase(
      ScoringSources sources,
      ScoreCalculator calculator,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    return new RecomputeScoresForEntityService(
        sources, calculator, properties.scoring().recomputeMaxPairs(), metrics, Clock.systemUTC());
  }

  @Bean
  MigrateAlgorithmVersionUseCase migrateAlgorithmVersionUseCase(
      ScoringSources sources,
      ScoreCalculator calculator,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    MatchingProperties.Scoring scoring = properties.scoring();
    return new MigrateAlgorithmVersionService(
        sources,
        calculator,
        scoring.migrationBatchSize(),
        scoring.snapshotRetention(),
        metrics,
        Clock.systemUTC());
  }

  // TD-035: la ventana de la cuota entra en la política para que el plazo nunca pueda ser menor.
  @Bean
  PurgeExpiredProfileViewsUseCase purgeExpiredProfileViewsUseCase(
      JdbcProfileViewLog views, UseCaseMetricsPort metrics, MatchingProperties properties) {
    MatchingProperties.Suggestions s = properties.suggestions();
    return new PurgeExpiredProfileViewsService(
        views,
        new ProfileViewRetentionPolicy(s.viewLogRetention(), s.window(), 5_000, 200),
        metrics,
        Clock.systemUTC());
  }

  @Bean
  ProfileViewRetentionJob profileViewRetentionJob(PurgeExpiredProfileViewsUseCase purge) {
    return new ProfileViewRetentionJob(purge);
  }

  @Bean
  ScoreMaintenanceWorker scoreMaintenanceWorker(MigrateAlgorithmVersionUseCase maintenance) {
    return new ScoreMaintenanceWorker(maintenance);
  }

  @Bean
  SnapshotApplicationProfileUseCase snapshotApplicationProfileUseCase(
      ScoringSources sources,
      CalculateCompatibilityScoreUseCase calculate,
      ScoreCalculator calculator,
      UseCaseMetricsPort metrics,
      MatchingProperties properties) {
    return new SnapshotApplicationProfileService(
        sources,
        calculate,
        calculator,
        properties.embedding().maxCharacters(),
        metrics,
        Clock.systemUTC());
  }

  @Bean
  CandidateJobAccess candidateJobAccess(
      CandidateAccessPort access, OwnedJobPort owned, JobSignalsReader jobs) {
    return new CandidateJobAccess(access, owned, jobs);
  }

  @Bean
  ExplainScoreUseCase explainScoreUseCase(
      CandidateJobAccess access,
      CalculateCompatibilityScoreUseCase calculate,
      JdbcEmbeddingWorkQueue queue,
      UseCaseMetricsPort metrics) {
    return new ExplainScoreService(access, calculate, queue, metrics);
  }

  @Bean
  WhatIfScoreUseCase whatIfScoreUseCase(
      CandidateJobAccess access,
      ScoringSources sources,
      ScoreCalculator calculator,
      UseCaseMetricsPort metrics) {
    return new WhatIfScoreService(access, sources, calculator, metrics);
  }
}
