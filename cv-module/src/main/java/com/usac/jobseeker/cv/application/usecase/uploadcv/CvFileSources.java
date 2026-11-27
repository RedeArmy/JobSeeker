package com.usac.jobseeker.cv.application.usecase.uploadcv;

import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Con qué trabaja la subida del CV: el perfil, sus versiones, el almacén, el antivirus y la
 * transacción corta en la que se registra la versión (TD-044). Se agrupan como `ScoringSources` en
 * `matching`, para que el caso de uso no reciba una lista larga de puertos del mismo nivel.
 */
public record CvFileSources(
    CandidateProfileStorePort profiles,
    CvFileStorePort files,
    ObjectStoragePort storage,
    MalwareScanPort scanner,
    TransactionOperations transactions) {

  public CvFileSources {
    Objects.requireNonNull(profiles, "profiles");
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(storage, "storage");
    Objects.requireNonNull(scanner, "scanner");
    Objects.requireNonNull(transactions, "transactions");
  }
}
