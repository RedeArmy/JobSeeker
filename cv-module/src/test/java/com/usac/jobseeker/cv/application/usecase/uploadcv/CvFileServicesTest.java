package com.usac.jobseeker.cv.application.usecase.uploadcv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.MalwareScanPort.ScanVerdict;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.application.usecase.downloadmycv.DownloadMyCvQuery;
import com.usac.jobseeker.cv.application.usecase.downloadmycv.DownloadMyCvService;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycv.GetMyCvQuery;
import com.usac.jobseeker.cv.application.usecase.getmycv.GetMyCvService;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.CandidateProfileStorePort;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CvFile;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Subir, consultar y descargar el CV propio (ADR-0021, `tdd/cv-module.md` §10.7). */
class CvFileServicesTest {

  private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
  private static final byte[] PDF = ascii("%PDF-1.7\nmi currículum\n%%EOF");

  private final AccountId me = new AccountId(UUID.randomUUID());
  private final List<String> metrics = new ArrayList<>();
  private final Profiles profiles = new Profiles();
  private final Files files = new Files();
  private final Storage storage = new Storage();
  private final Scanner scanner = new Scanner();
  private boolean isCandidate = true;

  private final CandidateChange change =
      new CandidateChange(
          profiles,
          profiles,
          a -> isCandidate,
          e -> {},
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          Clock.fixed(NOW, ZoneOffset.UTC),
          UUID::randomUUID);
  private final CvFilePolicy policy = new CvFilePolicy(1024, 3);
  private final UploadCvService upload =
      new UploadCvService(
          change,
          profiles,
          files,
          storage,
          scanner,
          policy,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          new Stamp(Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID));
  private final GetMyCvService getMyCv =
      new GetMyCvService(
          change,
          profiles,
          files,
          policy,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome));
  private final DownloadMyCvService download =
      new DownloadMyCvService(
          change,
          profiles,
          files,
          storage,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome));

  // ---- subir ----

  @Test
  void aValidPdfIsScannedStoredAndBecomesTheCurrentVersion() {
    aProfile();

    var view = upload.execute(new UploadCvCommand(me, "../CV Ana.exe", PDF));

    assertThat(view.current()).isTrue();
    assertThat(view.type()).isEqualTo("PDF");
    assertThat(view.displayName()).isEqualTo("CV Ana.pdf");
    assertThat(view.sizeBytes()).isEqualTo(PDF.length);
    assertThat(scanner.scanned).isOne();
    assertThat(storage.stored).hasSize(1);
    assertThat(storage.stored.get(0).value()).startsWith("cv/");
    assertThat(files.rows).singleElement().satisfies(f -> assertThat(f.current()).isTrue());
    assertThat(metrics).contains("upload_cv:uploaded");
  }

  @Test
  void aNewVersionReplacesTheCurrentAndKeepsThePreviousOne() {
    aProfile();
    var first = upload.execute(new UploadCvCommand(me, "v1.pdf", PDF));

    var second = upload.execute(new UploadCvCommand(me, "v2.pdf", ascii("%PDF-1.7\notro")));

    assertThat(files.rows).hasSize(2);
    assertThat(files.find(profiles.profile.id(), first.id()).orElseThrow().current()).isFalse();
    assertThat(second.current()).isTrue();
  }

  // Subir otra vez el mismo archivo no gasta una versión ni escanea de nuevo.
  @Test
  void theSameFileAsTheCurrentCreatesNoNewVersion() {
    aProfile();
    var first = upload.execute(new UploadCvCommand(me, "cv.pdf", PDF));

    var again = upload.execute(new UploadCvCommand(me, "otro nombre.pdf", PDF));

    assertThat(again.id()).isEqualTo(first.id());
    assertThat(files.rows).hasSize(1);
    assertThat(scanner.scanned).isOne();
    assertThat(metrics).contains("upload_cv:unchanged");
  }

  @Test
  void aFileOverTheLimitIsRejectedBeforeAnythingElse() {
    aProfile();
    byte[] big = new byte[2048];
    System.arraycopy(PDF, 0, big, 0, PDF.length);

    assertRejected(new UploadCvCommand(me, "cv.pdf", big), Reason.TOO_LARGE);
    assertThat(scanner.scanned).isZero();
    assertThat(storage.stored).isEmpty();
  }

  // ADR-0021 §2: el tipo sale del contenido. Una imagen o un DOCX con macros no son un CV.
  @Test
  void anythingThatIsNotAPdfOrAPlainDocxIsRejected() {
    aProfile();

    assertRejected(
        new UploadCvCommand(me, "cv.pdf", ascii("MZ ejecutable")), Reason.UNSUPPORTED_TYPE);
    assertRejected(
        new UploadCvCommand(
            me, "cv.pdf", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}),
        Reason.UNSUPPORTED_TYPE);
    assertRejected(new UploadCvCommand(me, "cv.pdf", new byte[0]), Reason.UNSUPPORTED_TYPE);
    assertThat(storage.stored).isEmpty();
  }

  @Test
  void malwareIsRejectedAndNeverStored() {
    aProfile();
    scanner.verdict = new ScanVerdict.Infected("Eicar-Test-Signature");

    assertRejected(new UploadCvCommand(me, "cv.pdf", PDF), Reason.MALWARE_DETECTED);
    assertThat(storage.stored).isEmpty();
    assertThat(files.rows).isEmpty();
  }

  // Fail-closed (ADR-0021 §7): sin antivirus no se guarda nada.
  @Test
  void anUnavailableScannerStoresNothing() {
    aProfile();
    scanner.down = true;
    var command = new UploadCvCommand(me, "cv.pdf", PDF);

    assertThatThrownBy(() -> upload.execute(command))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(storage.stored).isEmpty();
    assertThat(metrics).contains("upload_cv:unavailable");
  }

  @Test
  void theVersionLimitIsCheckedBeforeScanning() {
    aProfile();
    for (int i = 0; i < 3; i++) {
      upload.execute(new UploadCvCommand(me, "v.pdf", ascii("%PDF-1.7\nversión " + i)));
    }
    int scannedBefore = scanner.scanned;

    assertRejected(
        new UploadCvCommand(me, "v.pdf", ascii("%PDF-1.7\nla cuarta")), Reason.LIMIT_REACHED);
    assertThat(scanner.scanned).isEqualTo(scannedBefore);
  }

  // Bajo el bloqueo el tope se comprueba otra vez: lo subido de más se borra del almacén.
  @Test
  void aLimitReachedUnderTheLockDiscardsTheStoredObject() {
    aProfile();
    files.countAfterLock = 3;
    var command = new UploadCvCommand(me, "cv.pdf", PDF);

    assertThatThrownBy(() -> upload.execute(command)).isInstanceOf(FileRejectedException.class);
    assertThat(storage.stored).hasSize(1);
    assertThat(storage.deleted).containsExactlyElementsOf(storage.stored);
    assertThat(files.rows).isEmpty();
  }

  @Test
  void aFailedRegistrationDiscardsTheStoredObject() {
    aProfile();
    files.failInsert = true;
    var command = new UploadCvCommand(me, "cv.pdf", PDF);

    assertThatThrownBy(() -> upload.execute(command)).isInstanceOf(IllegalStateException.class);
    assertThat(storage.deleted).containsExactlyElementsOf(storage.stored);
  }

  @Test
  void anAccountWithoutTheCandidateCapabilityOrProfileCannotUpload() {
    var command = new UploadCvCommand(me, "cv.pdf", PDF);
    assertThatThrownBy(() -> upload.execute(command)).isInstanceOf(NotFoundException.class);

    aProfile();
    isCandidate = false;
    assertThatThrownBy(() -> upload.execute(command))
        .isInstanceOf(ForbiddenCapabilityException.class);
    assertThat(scanner.scanned).isZero();
  }

  @Test
  void theCommandNeverLeaksItsContentOrNameThroughToString() {
    var command = new UploadCvCommand(me, "CV Ana López.pdf", PDF);

    assertThat(command.toString()).doesNotContain("Ana", "PDF-1.7").contains("bytes=");
    assertThat(command.content()).isEqualTo(PDF).isNotSameAs(command.content());
  }

  // ---- consultar y descargar ----

  @Test
  void myCvListsEveryVersionNewestFirstWithTheCurrentOne() {
    aProfile();
    upload.execute(new UploadCvCommand(me, "v1.pdf", PDF));
    var second = upload.execute(new UploadCvCommand(me, "v2.pdf", ascii("%PDF-1.7\nv2")));

    var mine = getMyCv.execute(new GetMyCvQuery(me));

    assertThat(mine.current()).hasValueSatisfying(c -> assertThat(c.id()).isEqualTo(second.id()));
    assertThat(mine.versions()).hasSize(2);
    assertThat(mine.maxVersions()).isEqualTo(3);
  }

  @Test
  void aDownloadIsASignedUrlForAVersionOfMyOwn() {
    aProfile();
    var mine = upload.execute(new UploadCvCommand(me, "Mi CV.pdf", PDF));

    URI url = download.execute(new DownloadMyCvQuery(me, mine.id()));

    assertThat(url.toString()).contains("Mi%20CV.pdf");
    assertThat(metrics).contains("download_my_cv:issued");
  }

  // Una versión ajena no existe para quien la pide: 404, no 403.
  @Test
  void aVersionThatIsNotMineDoesNotExist() {
    aProfile();
    var query = new DownloadMyCvQuery(me, UUID.randomUUID());

    assertThatThrownBy(() -> download.execute(query)).isInstanceOf(NotFoundException.class);
    assertThat(storage.presigned).isZero();
  }

  private void assertRejected(UploadCvCommand command, Reason reason) {
    assertThatThrownBy(() -> upload.execute(command))
        .isInstanceOfSatisfying(
            FileRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  private void aProfile() {
    profiles.profile =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            me,
            CandidateDetails.of("a@b.co", null, null, null, null, null, null, null, null, null),
            NOW);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static final class Profiles
      implements CandidateProfileStorePort, CandidateCollectionsReadPort {
    CandidateProfile profile;

    @Override
    public Optional<CandidateProfile> findByAccountId(AccountId accountId) {
      return Optional.ofNullable(profile).filter(p -> p.accountId().equals(accountId));
    }

    @Override
    public boolean insertIfAbsent(CandidateProfile candidate) {
      return false;
    }

    @Override
    public boolean updateIfVersion(CandidateProfile candidate, long expectedVersion) {
      return false;
    }

    @Override
    public CandidateCollections findByProfileId(CandidateProfileId profileId) {
      throw new UnsupportedOperationException("el CV no lee las colecciones");
    }
  }

  private static final class Files implements CvFileStorePort {
    final List<CvFile> rows = new ArrayList<>();
    Integer countAfterLock;
    boolean locked;
    boolean failInsert;

    @Override
    public Optional<CvFile> current(CandidateProfileId profile) {
      return rows.stream().filter(f -> f.profileId().equals(profile) && f.current()).findFirst();
    }

    @Override
    public List<CvFile> versions(CandidateProfileId profile) {
      return rows.stream()
          .filter(f -> f.profileId().equals(profile))
          .sorted(Comparator.comparing(CvFile::uploadedAt).reversed())
          .toList();
    }

    @Override
    public Optional<CvFile> find(CandidateProfileId profile, UUID fileId) {
      return rows.stream()
          .filter(f -> f.profileId().equals(profile) && f.id().equals(fileId))
          .findFirst();
    }

    @Override
    public void lockProfile(CandidateProfileId profile) {
      locked = true;
    }

    @Override
    public int count(CandidateProfileId profile) {
      if (locked && countAfterLock != null) {
        return countAfterLock;
      }
      return (int) rows.stream().filter(f -> f.profileId().equals(profile)).count();
    }

    @Override
    public void insertCurrent(CvFile file) {
      if (failInsert) {
        throw new IllegalStateException("fallo simulado de la base");
      }
      rows.replaceAll(
          f ->
              f.profileId().equals(file.profileId()) && f.current()
                  ? new CvFile(
                      f.id(),
                      f.profileId(),
                      f.objectKey(),
                      f.displayName(),
                      f.type(),
                      f.sizeBytes(),
                      f.sha256(),
                      false,
                      f.uploadedAt())
                  : f);
      rows.add(file);
      locked = false;
    }
  }

  private static final class Storage implements ObjectStoragePort {
    final List<StoredObjectKey> stored = new ArrayList<>();
    final List<StoredObjectKey> deleted = new ArrayList<>();
    int presigned;

    @Override
    public StoredObjectKey put(FilePurpose purpose, byte[] content, FileType type) {
      var key = StoredObjectKey.random(purpose);
      stored.add(key);
      return key;
    }

    @Override
    public URI presignDownload(StoredObjectKey key, String displayName, FileType type) {
      presigned++;
      return URI.create(
          "https://almacen.test/" + key.value() + "?name=" + displayName.replace(" ", "%20"));
    }

    @Override
    public void delete(StoredObjectKey key) {
      deleted.add(key);
    }

    @Override
    public boolean exists(StoredObjectKey key) {
      return stored.contains(key) && !deleted.contains(key);
    }
  }

  private static final class Scanner implements MalwareScanPort {
    ScanVerdict verdict = new ScanVerdict.Clean();
    boolean down;
    int scanned;

    @Override
    public ScanVerdict scan(byte[] content) {
      if (down) {
        throw new UpstreamUnavailableException("malware_scan", new IOException("caído"));
      }
      scanned++;
      return verdict;
    }
  }
}
