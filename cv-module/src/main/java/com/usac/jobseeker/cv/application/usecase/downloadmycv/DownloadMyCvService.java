package com.usac.jobseeker.cv.application.usecase.downloadmycv;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import com.usac.jobseeker.cv.application.usecase.uploadcv.CvFileStorePort;
import com.usac.jobseeker.cv.domain.model.CvFile;
import java.net.URI;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La versión se busca dentro
 * del perfil de quien llama, así que un identificador ajeno es un 404 sin consulta extra.
 */
public class DownloadMyCvService implements DownloadMyCvUseCase {

  private static final String USE_CASE = "download_my_cv";

  private final CandidateChange change;
  private final CandidateProfileStorePort profiles;
  private final CvFileStorePort files;
  private final ObjectStoragePort storage;
  private final UseCaseMetricsPort metrics;

  public DownloadMyCvService(
      CandidateChange change,
      CandidateProfileStorePort profiles,
      CvFileStorePort files,
      ObjectStoragePort storage,
      UseCaseMetricsPort metrics) {
    this.change = Objects.requireNonNull(change, "change");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.files = Objects.requireNonNull(files, "files");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public URI execute(DownloadMyCvQuery query) {
    change.requireCandidate(USE_CASE, query.accountId());
    CvFile file =
        profiles
            .findByAccountId(query.accountId())
            .flatMap(profile -> files.find(profile.id(), query.fileId()))
            .orElseThrow(
                () -> {
                  metrics.count(USE_CASE, "not_found");
                  return new NotFoundException();
                });
    URI url =
        storage.presignDownload(
            new StoredObjectKey(file.objectKey()), file.displayName(), file.type());
    metrics.count(USE_CASE, "issued");
    return url;
  }
}
