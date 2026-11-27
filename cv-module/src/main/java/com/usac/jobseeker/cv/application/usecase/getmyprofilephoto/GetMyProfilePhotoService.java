package com.usac.jobseeker.cv.application.usecase.getmyprofilephoto;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.ProfilePhotoStorePort;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.UploadProfilePhotoService;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). */
public class GetMyProfilePhotoService implements GetMyProfilePhotoUseCase {

  private static final String USE_CASE = "get_my_profile_photo";

  private final ProfilePhotoStorePort photos;
  private final ObjectStoragePort storage;
  private final UseCaseMetricsPort metrics;

  public GetMyProfilePhotoService(
      ProfilePhotoStorePort photos, ObjectStoragePort storage, UseCaseMetricsPort metrics) {
    this.photos = Objects.requireNonNull(photos, "photos");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProfilePhotoView> describe(GetMyProfilePhotoQuery query) {
    return photos
        .find(query.accountId())
        .filter(ProfilePhotoStorePort.PhotoSlot::hasPhoto)
        .map(
            slot ->
                new ProfilePhotoView(
                    slot.type().name(), UploadProfilePhotoService.tagOf(slot.key())));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProfilePhotoContent> read(GetMyProfilePhotoQuery query) {
    var slot = photos.find(query.accountId()).filter(ProfilePhotoStorePort.PhotoSlot::hasPhoto);
    if (slot.isEmpty()) {
      metrics.count(USE_CASE, "none");
      return Optional.empty();
    }
    var content = storage.get(new StoredObjectKey(slot.get().key()));
    metrics.count(USE_CASE, content.isPresent() ? "served" : "missing_object");
    return content.map(bytes -> new ProfilePhotoContent(bytes, slot.get().type().mediaType()));
  }
}
