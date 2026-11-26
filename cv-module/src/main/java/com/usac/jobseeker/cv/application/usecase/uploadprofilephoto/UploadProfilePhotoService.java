package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.MalwareScanPort.ScanVerdict;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileContentRules;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.support.FileDeletionRequests;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.ProfilePhotoView;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.PhotoProcessorPort.ProcessedPhoto;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.ProfilePhotoStorePort.PhotoSlot;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El orden protege al
 * decodificador de imágenes: el antivirus revisa el archivo **original** antes de abrirlo, y solo
 * después se decodifica y se re-codifica (`tdd/cv-module.md` §10.8). La foto anterior se borra por
 * la bandeja en la misma transacción, así que si el alta falla la anterior sigue ahí.
 *
 * <p>La foto es del perfil profesional, común a todas las capacidades de la cuenta (ADR-0008): no
 * exige la de candidato, solo tener perfil.
 */
public class UploadProfilePhotoService implements UploadProfilePhotoUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(UploadProfilePhotoService.class);
  private static final String USE_CASE = "upload_profile_photo";

  /** Lo que se acepta como foto (ADR-0021 §4, decisión R2). */
  private static final Set<FileType> ACCEPTED = Set.of(FileType.PNG, FileType.JPEG);

  private final ProfilePhotoSources sources;
  private final ProfilePhotoPolicy policy;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Stamp stamp;

  public UploadProfilePhotoService(
      ProfilePhotoSources sources,
      ProfilePhotoPolicy policy,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Stamp stamp) {
    this.sources = Objects.requireNonNull(sources, "sources");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.stamp = Objects.requireNonNull(stamp, "stamp");
  }

  // Cualquier fallo tras el `put` deja un objeto sin referencia: se borra y se relanza tal cual.
  @Override
  @Transactional
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public ProfilePhotoView execute(UploadProfilePhotoCommand command) {
    if (sources.photos().find(command.accountId()).isEmpty()) {
      metrics.count(USE_CASE, "no_profile");
      throw new NotFoundException();
    }
    byte[] content = command.content();
    if (content.length > policy.maxBytes()) {
      throw rejected(Reason.TOO_LARGE, "too_large");
    }
    FileType type =
        FileContentRules.detect(content)
            .filter(ACCEPTED::contains)
            .orElseThrow(() -> rejected(Reason.UNSUPPORTED_TYPE, "unsupported_type"));
    scan(content);
    ProcessedPhoto processed = process(content, type);
    StoredObjectKey key = put(processed);
    try {
      PhotoSlot slot =
          sources.photos().lock(command.accountId()).orElseThrow(NotFoundException::new);
      sources.photos().set(slot.profileId(), key.value(), processed.type());
      if (slot.hasPhoto()) {
        FileDeletionRequests.request(
            events,
            stamp,
            ProfileUpdated.AGGREGATE_TYPE,
            slot.profileId().value(),
            slot.version(),
            List.of(new StoredObjectKey(slot.key())));
      }
      metrics.count(USE_CASE, "uploaded");
      LOG.info("event=profile_photo_uploaded Foto de perfil guardada");
      return new ProfilePhotoView(processed.type().name(), tagOf(key.value()));
    } catch (RuntimeException e) {
      discard(key);
      throw e;
    }
  }

  /**
   * Una etiqueta corta derivada de la clave aleatoria: cambia con cada foto y no dice de quién es.
   */
  public static String tagOf(String key) {
    return key.substring(key.length() - 12);
  }

  private void scan(byte[] content) {
    try {
      if (sources.scanner().scan(content) instanceof ScanVerdict.Infected(String signature)) {
        LOG.warn(
            "event=profile_photo_rejected outcome=malware signature={} Se rechazó una foto con"
                + " malware",
            signature);
        throw rejected(Reason.MALWARE_DETECTED, "malware");
      }
    } catch (UpstreamUnavailableException e) {
      metrics.count(USE_CASE, "unavailable");
      throw e;
    }
  }

  private ProcessedPhoto process(byte[] content, FileType type) {
    try {
      return sources.processor().process(content, type);
    } catch (FileRejectedException e) {
      metrics.count(USE_CASE, "unreadable_image");
      throw e;
    }
  }

  private StoredObjectKey put(ProcessedPhoto photo) {
    try {
      return sources.storage().put(FilePurpose.PHOTO, photo.content(), photo.type());
    } catch (UpstreamUnavailableException e) {
      metrics.count(USE_CASE, "unavailable");
      throw e;
    }
  }

  // Best effort: si falla, el objeto queda huérfano hasta la reconciliación periódica (TD-041).
  private void discard(StoredObjectKey key) {
    try {
      sources.storage().delete(key);
    } catch (UpstreamUnavailableException _) {
      LOG.warn("event=profile_photo_orphan_object No se pudo borrar un objeto de un alta fallida");
    }
  }

  private FileRejectedException rejected(Reason reason, String outcome) {
    metrics.count(USE_CASE, outcome);
    return new FileRejectedException(reason);
  }
}
