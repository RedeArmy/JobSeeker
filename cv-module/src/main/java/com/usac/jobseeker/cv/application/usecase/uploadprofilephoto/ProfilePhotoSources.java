package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Con qué trabaja la subida de la foto: su hueco en el perfil, el almacén, el antivirus y el
 * re-codificador. Se agrupan como `CvFileSources` en la subida del CV.
 */
public record ProfilePhotoSources(
    ProfilePhotoStorePort photos,
    ObjectStoragePort storage,
    MalwareScanPort scanner,
    PhotoProcessorPort processor,
    TransactionOperations transactions) {

  public ProfilePhotoSources {
    Objects.requireNonNull(photos, "photos");
    Objects.requireNonNull(storage, "storage");
    Objects.requireNonNull(scanner, "scanner");
    Objects.requireNonNull(processor, "processor");
    Objects.requireNonNull(transactions, "transactions");
  }
}
