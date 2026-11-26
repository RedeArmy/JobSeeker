package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.MalwareScanPort.ScanVerdict;
import com.usac.jobseeker.common.application.storage.ObjectPage;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoQuery;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoService;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoCommand;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoService;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La foto de perfil (`tdd/cv-module.md` §10.8, ADR-0021): qué se acepta, qué se guarda, cuándo se
 * borra la anterior y que nunca queda un objeto subido sin referencia.
 */
class ProfilePhotoServicesTest {

  private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2};
  private static final byte[] PDF = "%PDF-1.7 no soy una foto".getBytes();

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final Photos photos = new Photos();
  private final Store storage = new Store();
  private final Scanner scanner = new Scanner();
  private final List<OutboxEvent> events = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final Stamp stamp = new Stamp(Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
  private boolean processorRejects;

  private final PhotoProcessorPort processor =
      (content, type) -> {
        if (processorRejects) {
          throw new FileRejectedException(Reason.UNSUPPORTED_TYPE);
        }
        return new PhotoProcessorPort.ProcessedPhoto(new byte[] {9, 9, 9}, type);
      };

  private UploadProfilePhotoService upload(long maxBytes) {
    return new UploadProfilePhotoService(
        new ProfilePhotoSources(photos, storage, scanner, processor),
        new ProfilePhotoPolicy(maxBytes),
        events::add,
        (useCase, outcome) -> metrics.add(outcome),
        stamp);
  }

  private UploadProfilePhotoService upload() {
    return upload(2_000_000);
  }

  @Test
  void aValidPhotoIsScannedProcessedAndStoredInsteadOfTheOriginal() {
    photos.create(account);

    var view = upload().execute(new UploadProfilePhotoCommand(account, PNG));

    assertThat(scanner.scanned).isEqualTo(1);
    String key = photos.slot.key();
    assertThat(key).startsWith("photo/");
    assertThat(storage.objects.get(key)).containsExactly(9, 9, 9);
    assertThat(view.type()).isEqualTo("PNG");
    assertThat(view.tag()).isEqualTo(key.substring(key.length() - 12));
    assertThat(events).isEmpty();
    assertThat(metrics).containsExactly("uploaded");
  }

  // ADR-0021 §6: la foto reemplazada se borra del almacén por la bandeja, nunca en línea.
  @Test
  void replacingAPhotoRequestsTheDeletionOfThePreviousOne() {
    photos.create(account);
    upload().execute(new UploadProfilePhotoCommand(account, PNG));
    String first = photos.slot.key();

    upload().execute(new UploadProfilePhotoCommand(account, PNG));

    assertThat(photos.slot.key()).isNotEqualTo(first);
    assertThat(storage.objects).containsKey(first);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).eventType()).isEqualTo("storage.file_deletion_requested");
    assertThat(events.get(0).payload()).contains(first);
  }

  @Test
  void withoutAProfessionalProfileThereIsNowhereToStoreIt() {
    var service = upload();
    var command = new UploadProfilePhotoCommand(account, PNG);

    assertThatThrownBy(() -> service.execute(command)).isInstanceOf(NotFoundException.class);
    assertThat(storage.objects).isEmpty();
  }

  @Test
  void aPhotoThatIsTooLargeOrNotAnImageIsRejectedWithoutScanning() {
    photos.create(account);
    var small = upload(5);
    var service = upload();

    assertReason(
        () -> small.execute(new UploadProfilePhotoCommand(account, PNG)), Reason.TOO_LARGE);
    assertReason(
        () -> service.execute(new UploadProfilePhotoCommand(account, PDF)),
        Reason.UNSUPPORTED_TYPE);
    assertThat(scanner.scanned).isZero();
    assertThat(storage.objects).isEmpty();
  }

  @Test
  void anInfectedPhotoIsRejectedAndNeverStored() {
    photos.create(account);
    scanner.verdict = new ScanVerdict.Infected("Eicar-Test-Signature");
    var service = upload();

    assertReason(
        () -> service.execute(new UploadProfilePhotoCommand(account, PNG)),
        Reason.MALWARE_DETECTED);
    assertThat(storage.objects).isEmpty();
    assertThat(metrics).containsExactly("malware");
  }

  @Test
  void anImageThatCannotBeDecodedIsRejected() {
    photos.create(account);
    processorRejects = true;
    var service = upload();

    assertReason(
        () -> service.execute(new UploadProfilePhotoCommand(account, PNG)),
        Reason.UNSUPPORTED_TYPE);
    assertThat(storage.objects).isEmpty();
    assertThat(metrics).containsExactly("unreadable_image");
  }

  // Fail-closed (ADR-0021 §5): sin antivirus no se guarda nada.
  @Test
  void anUnavailableScannerStoresNothing() {
    photos.create(account);
    scanner.down = true;
    var service = upload();
    var command = new UploadProfilePhotoCommand(account, PNG);

    assertThatThrownBy(() -> service.execute(command))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(storage.objects).isEmpty();
    assertThat(metrics).containsExactly("unavailable");
  }

  // Un fallo después de subir el objeto no lo deja huérfano.
  @Test
  void aFailureAfterStoringDiscardsTheNewObject() {
    photos.create(account);
    photos.failOnSet = true;
    var service = upload();
    var command = new UploadProfilePhotoCommand(account, PNG);

    assertThatThrownBy(() -> service.execute(command)).isInstanceOf(IllegalStateException.class);
    assertThat(storage.objects).isEmpty();
    assertThat(storage.deleted).hasSize(1);
  }

  @Test
  void removingThePhotoClearsItAndRequestsItsDeletionAndDoingItAgainIsHarmless() {
    photos.create(account);
    upload().execute(new UploadProfilePhotoCommand(account, PNG));
    String key = photos.slot.key();
    var remove =
        new RemoveProfilePhotoService(photos, events::add, (u, o) -> metrics.add(o), stamp);

    remove.execute(new RemoveProfilePhotoCommand(account));
    remove.execute(new RemoveProfilePhotoCommand(account));

    assertThat(photos.slot.hasPhoto()).isFalse();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).payload()).contains(key);
    assertThat(metrics).endsWith("removed", "nothing");
  }

  @Test
  void removingWithoutAProfileIsNotFound() {
    var remove =
        new RemoveProfilePhotoService(photos, events::add, (u, o) -> metrics.add(o), stamp);
    var command = new RemoveProfilePhotoCommand(account);

    assertThatThrownBy(() -> remove.execute(command)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void readingThePhotoServesItsBytesWithItsMediaType() {
    photos.create(account);
    var get = new GetMyProfilePhotoService(photos, storage, (u, o) -> metrics.add(o));
    var query = new GetMyProfilePhotoQuery(account);
    assertThat(get.describe(query)).isEmpty();
    assertThat(get.read(query)).isEmpty();

    var view = upload().execute(new UploadProfilePhotoCommand(account, PNG));

    assertThat(get.describe(query)).contains(view);
    assertThat(get.read(query))
        .hasValueSatisfying(
            photo -> {
              assertThat(photo.content()).containsExactly(9, 9, 9);
              assertThat(photo.mediaType()).isEqualTo("image/png");
            });
  }

  // La fila apunta a un objeto que ya no está (borrado a mano, o la reconciliación): no hay foto.
  @Test
  void aMissingObjectReadsAsNoPhoto() {
    photos.create(account);
    upload().execute(new UploadProfilePhotoCommand(account, PNG));
    storage.objects.clear();
    var get = new GetMyProfilePhotoService(photos, storage, (u, o) -> metrics.add(o));

    assertThat(get.read(new GetMyProfilePhotoQuery(account))).isEmpty();
    assertThat(metrics).endsWith("missing_object");
  }

  // La imagen procesada se compara por contenido y no se puede cambiar desde fuera.
  @Test
  void aProcessedPhotoComparesByContentAndCopiesItsBytes() {
    byte[] bytes = {1, 2, 3};
    var photo = new PhotoProcessorPort.ProcessedPhoto(bytes, FileType.PNG);
    bytes[0] = 9;
    photo.content()[1] = 9;

    assertThat(photo.content()).containsExactly(1, 2, 3);
    assertThat(photo)
        .isEqualTo(new PhotoProcessorPort.ProcessedPhoto(new byte[] {1, 2, 3}, FileType.PNG))
        .hasSameHashCodeAs(
            new PhotoProcessorPort.ProcessedPhoto(new byte[] {1, 2, 3}, FileType.PNG))
        .isNotEqualTo(new PhotoProcessorPort.ProcessedPhoto(new byte[] {1, 2, 3}, FileType.JPEG))
        .isNotEqualTo(new PhotoProcessorPort.ProcessedPhoto(new byte[] {1, 2}, FileType.PNG));
    assertThat(photo.toString()).isEqualTo("ProcessedPhoto[PNG, bytes=3]");
  }

  private static void assertReason(Runnable call, Reason reason) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            FileRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  /** El perfil de una sola cuenta, en memoria. */
  private static final class Photos implements ProfilePhotoStorePort {
    AccountId owner;
    PhotoSlot slot;
    boolean failOnSet;

    void create(AccountId account) {
      owner = account;
      slot = new PhotoSlot(new ProfessionalProfileId(UUID.randomUUID()), 1, null, null);
    }

    @Override
    public Optional<PhotoSlot> find(AccountId account) {
      return account.equals(owner) ? Optional.of(slot) : Optional.empty();
    }

    @Override
    public Optional<PhotoSlot> lock(AccountId account) {
      return find(account);
    }

    @Override
    public void set(ProfessionalProfileId profile, String key, FileType type) {
      if (failOnSet) {
        throw new IllegalStateException("fallo simulado al confirmar");
      }
      slot = new PhotoSlot(profile, slot.version(), key, type);
    }
  }

  private static final class Scanner implements MalwareScanPort {
    ScanVerdict verdict = new ScanVerdict.Clean();
    boolean down;
    int scanned;

    @Override
    public ScanVerdict scan(byte[] content) {
      if (down) {
        throw new UpstreamUnavailableException("clamav", new IOException("caído"));
      }
      scanned++;
      return verdict;
    }
  }

  private static final class Store implements ObjectStoragePort {
    final Map<String, byte[]> objects = new HashMap<>();
    final List<String> deleted = new ArrayList<>();

    @Override
    public StoredObjectKey put(FilePurpose purpose, byte[] content, FileType type) {
      var key = StoredObjectKey.random(purpose);
      objects.put(key.value(), content.clone());
      return key;
    }

    @Override
    public Optional<byte[]> get(StoredObjectKey key) {
      return Optional.ofNullable(objects.get(key.value()));
    }

    @Override
    public void delete(StoredObjectKey key) {
      deleted.add(key.value());
      objects.remove(key.value());
    }

    @Override
    public URI presignDownload(StoredObjectKey key, String displayName, FileType type) {
      throw new UnsupportedOperationException("la foto no se descarga por URL firmada");
    }

    @Override
    public boolean exists(StoredObjectKey key) {
      return objects.containsKey(key.value());
    }

    @Override
    public ObjectPage list(FilePurpose purpose, String startAfter, int maxKeys) {
      throw new UnsupportedOperationException("la foto no lista el almacén");
    }
  }
}
