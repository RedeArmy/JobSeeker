package com.usac.jobseeker.cv.application.usecase.uploadcv;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.MalwareScanPort.ScanVerdict;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileContentRules;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.application.usecase.getmycv.CvFileView;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CvFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El orden es el de
 * `tdd/cv-module.md` §10.7: lo barato y lo que no necesita servicios externos primero, el escaneo
 * antes de guardar (nada sin escanear llega al almacén, ADR-0021 §2) y el alta bajo el bloqueo del
 * perfil, que hace exacto el tope de versiones.
 *
 * <p>Ni el log ni la métrica llevan el nombre del archivo ni la cuenta (BR-DATA-01); la firma del
 * antivirus sí va al log, porque identifica la amenaza y no a la persona.
 */
public class UploadCvService implements UploadCvUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(UploadCvService.class);
  private static final String USE_CASE = "upload_cv";

  private final CandidateChange change;
  private final CandidateProfileStorePort profiles;
  private final CvFileStorePort files;
  private final ObjectStoragePort storage;
  private final MalwareScanPort scanner;
  private final CvFilePolicy policy;
  private final UseCaseMetricsPort metrics;
  private final Stamp stamp;

  public UploadCvService(
      CandidateChange change,
      CandidateProfileStorePort profiles,
      CvFileStorePort files,
      ObjectStoragePort storage,
      MalwareScanPort scanner,
      CvFilePolicy policy,
      UseCaseMetricsPort metrics,
      Stamp stamp) {
    this.change = Objects.requireNonNull(change, "change");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.files = Objects.requireNonNull(files, "files");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.scanner = Objects.requireNonNull(scanner, "scanner");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.stamp = Objects.requireNonNull(stamp, "stamp");
  }

  // Cualquier fallo tras el `put` deja un objeto sin referencia: se borra y se relanza tal cual.
  @Override
  @Transactional
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public CvFileView execute(UploadCvCommand command) {
    change.requireCandidate(USE_CASE, command.accountId());
    CandidateProfile profile =
        profiles.findByAccountId(command.accountId()).orElseThrow(this::notFound);
    byte[] content = command.content();
    if (content.length > policy.maxBytes()) {
      throw rejected(Reason.TOO_LARGE, "too_large");
    }
    FileType type =
        FileContentRules.detect(content)
            .filter(CvFile.ACCEPTED_TYPES::contains)
            .orElseThrow(() -> rejected(Reason.UNSUPPORTED_TYPE, "unsupported_type"));
    String sha256 = sha256(content);
    Optional<CvFile> current = files.current(profile.id());
    if (current.isPresent() && current.get().sha256().equals(sha256)) {
      metrics.count(USE_CASE, "unchanged");
      return CvFileView.of(current.get());
    }
    if (files.count(profile.id()) >= policy.maxVersions()) {
      throw rejected(Reason.LIMIT_REACHED, "limit_reached");
    }
    scan(content);
    StoredObjectKey key = put(content, type);
    try {
      CvFile stored = register(profile, key, command.originalName(), type, content, sha256);
      metrics.count(USE_CASE, "uploaded");
      LOG.info("event=cv_uploaded outcome=uploaded Versión del CV guardada");
      return CvFileView.of(stored);
    } catch (RuntimeException e) {
      discard(key);
      throw e;
    }
  }

  // El tope se vuelve a comprobar bajo el bloqueo: otra subida pudo llegar mientras se escaneaba.
  private CvFile register(
      CandidateProfile profile,
      StoredObjectKey key,
      String originalName,
      FileType type,
      byte[] content,
      String sha256) {
    files.lockProfile(profile.id());
    if (files.count(profile.id()) >= policy.maxVersions()) {
      throw rejected(Reason.LIMIT_REACHED, "limit_reached");
    }
    CvFile file =
        new CvFile(
            stamp.ids().get(),
            profile.id(),
            key.value(),
            FileContentRules.displayName(originalName, type),
            type,
            content.length,
            sha256,
            true,
            stamp.clock().instant());
    files.insertCurrent(file);
    return file;
  }

  private void scan(byte[] content) {
    try {
      ScanVerdict verdict = scanner.scan(content);
      if (verdict instanceof ScanVerdict.Infected infected) {
        LOG.warn(
            "event=cv_rejected outcome=malware signature={} Se rechazó un CV con malware",
            infected.signature());
        throw rejected(Reason.MALWARE_DETECTED, "malware");
      }
    } catch (UpstreamUnavailableException e) {
      metrics.count(USE_CASE, "unavailable");
      throw e;
    }
  }

  private StoredObjectKey put(byte[] content, FileType type) {
    try {
      return storage.put(FilePurpose.CV, content, type);
    } catch (UpstreamUnavailableException e) {
      metrics.count(USE_CASE, "unavailable");
      throw e;
    }
  }

  // Best effort: si el alta falló, el objeto no lo referencia nadie. Si el borrado también falla,
  // queda huérfano hasta la reconciliación periódica (ADR-0021 §2); el error original es el que
  // importa a quien llama.
  private void discard(StoredObjectKey key) {
    try {
      storage.delete(key);
    } catch (UpstreamUnavailableException e) {
      LOG.warn("event=cv_orphan_object No se pudo borrar un objeto de un alta fallida");
    }
  }

  private FileRejectedException rejected(Reason reason, String outcome) {
    metrics.count(USE_CASE, outcome);
    return new FileRejectedException(reason);
  }

  private NotFoundException notFound() {
    metrics.count(USE_CASE, "no_profile");
    return new NotFoundException();
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      // Toda JVM está obligada a ofrecer SHA-256: esto no puede pasar.
      throw new IllegalStateException(e);
    }
  }
}
