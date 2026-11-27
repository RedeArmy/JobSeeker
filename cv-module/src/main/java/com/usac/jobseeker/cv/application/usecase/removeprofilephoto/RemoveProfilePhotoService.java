package com.usac.jobseeker.cv.application.usecase.removeprofilephoto;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.outbox.DomainEventPublisherPort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.support.FileDeletionRequests;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.ProfilePhotoStorePort;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). Quitar la foto y pedir su
 * borrado van en la misma transacción (ADR-0021 §6).
 */
public class RemoveProfilePhotoService implements RemoveProfilePhotoUseCase {

  private static final String USE_CASE = "remove_profile_photo";

  private final ProfilePhotoStorePort photos;
  private final DomainEventPublisherPort events;
  private final UseCaseMetricsPort metrics;
  private final Stamp stamp;

  public RemoveProfilePhotoService(
      ProfilePhotoStorePort photos,
      DomainEventPublisherPort events,
      UseCaseMetricsPort metrics,
      Stamp stamp) {
    this.photos = Objects.requireNonNull(photos, "photos");
    this.events = Objects.requireNonNull(events, "events");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.stamp = Objects.requireNonNull(stamp, "stamp");
  }

  @Override
  @Transactional
  public void execute(RemoveProfilePhotoCommand command) {
    var slot =
        photos
            .lock(command.accountId())
            .orElseThrow(
                () -> {
                  metrics.count(USE_CASE, "no_profile");
                  return new NotFoundException();
                });
    if (!slot.hasPhoto()) {
      metrics.count(USE_CASE, "nothing");
      return;
    }
    photos.set(slot.profileId(), null, null);
    FileDeletionRequests.request(
        events,
        stamp,
        ProfileUpdated.AGGREGATE_TYPE,
        slot.profileId().value(),
        slot.version(),
        List.of(new StoredObjectKey(slot.key())));
    metrics.count(USE_CASE, "removed");
  }
}
