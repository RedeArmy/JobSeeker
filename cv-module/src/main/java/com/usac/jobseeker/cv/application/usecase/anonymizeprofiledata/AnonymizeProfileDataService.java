package com.usac.jobseeker.cv.application.usecase.anonymizeprofiledata;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.cv.application.support.FileDeletionRequests;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.domain.event.ProfileDataAnonymized;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Anonymization;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El caso de uso es el límite
 * transaccional: los dos perfiles y el evento se escriben juntos o ninguno, así que no existe un
 * estado a medias que reanudar. Una cuenta con ambos perfiles emite **un** evento, no uno por
 * perfil.
 *
 * <p>Ni el log ni la métrica llevan el identificador de la cuenta ni dato alguno (BR-DATA-01).
 */
public class AnonymizeProfileDataService implements AnonymizeProfileDataUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AnonymizeProfileDataService.class);
  private static final String USE_CASE = "anonymize_profile_data";
  private static final String TRACE_ID_KEY = "traceId";

  private final AnonymizationStorePort store;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Stamp stamp;

  public AnonymizeProfileDataService(
      AnonymizationStorePort store,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Stamp stamp) {
    this.store = Objects.requireNonNull(store, "store");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.stamp = Objects.requireNonNull(stamp, "stamp");
  }

  @Override
  @Transactional
  public Result execute(AnonymizeProfileDataCommand command) {
    var now = stamp.clock().instant();
    // Antes de anonimizar: después la clave de la foto ya sería nula y no se podría pedir su
    // borrado.
    Optional<String> photo = store.discardProfilePhoto(command.accountId());
    Optional<AnonymizationStorePort.Anonymized> professional =
        store.anonymizeProfessional(command.accountId(), Anonymization.PLACEHOLDER_NAME, now);
    Optional<AnonymizationStorePort.Anonymized> candidate =
        store.anonymizeCandidate(command.accountId(), now);
    // El perfil profesional es el agregado raíz de la cuenta: el evento se asocia a él, y solo si
    // no tenía (no puede ocurrir con un candidato) al de candidato.
    Optional<AnonymizationStorePort.Anonymized> anonymized = professional.or(() -> candidate);
    if (anonymized.isEmpty()) {
      metrics.count(USE_CASE, "nothing");
      LOG.info("event=profile_data_anonymized outcome=nothing Sin datos de perfil que anonimizar");
      return Result.NOTHING_TO_ANONYMIZE;
    }
    events.publish(
        new OutboxEvent(
            stamp.ids().get(),
            professional.isPresent()
                ? ProfileUpdated.AGGREGATE_TYPE
                : ProfileUpdated.CANDIDATE_AGGREGATE_TYPE,
            anonymized.get().profileId(),
            anonymized.get().version(),
            ProfileDataAnonymized.EVENT_TYPE,
            OutboxEvent.FIRST_EVENT_VERSION,
            ProfileDataAnonymized.payload(command.accountId(), anonymized.get().version()),
            MDC.get(TRACE_ID_KEY)));
    requestFileDeletion(professional.isPresent(), anonymized.get(), photo, candidate, now);
    metrics.count(USE_CASE, "anonymized");
    LOG.info("event=profile_data_anonymized outcome=anonymized Datos de perfil anonimizados");
    return Result.ANONYMIZED;
  }

  // ADR-0021 §6: el borrado físico de la foto y de los CV va por la bandeja, en esta misma
  // transacción y en un solo evento. Si la anonimización se deshace, no se borra nada; si el
  // almacén
  // falla, el consumidor reintenta.
  private void requestFileDeletion(
      boolean professionalRoot,
      AnonymizationStorePort.Anonymized root,
      Optional<String> photo,
      Optional<AnonymizationStorePort.Anonymized> candidate,
      Instant now) {
    List<StoredObjectKey> keys = new ArrayList<>();
    photo.map(StoredObjectKey::new).ifPresent(keys::add);
    candidate.ifPresent(
        profile ->
            store.discardCvFiles(profile.profileId(), now).stream()
                .map(StoredObjectKey::new)
                .forEach(keys::add));
    FileDeletionRequests.request(
        events,
        stamp,
        professionalRoot ? ProfileUpdated.AGGREGATE_TYPE : ProfileUpdated.CANDIDATE_AGGREGATE_TYPE,
        root.profileId(),
        root.version(),
        keys);
  }
}
