package com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.ObjectPage;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileType;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** La reconciliación de CV huérfanos (TD-041): qué borra, qué nunca toca y cuándo se frena. */
class ReconcileOrphanCvFilesServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
  private static final Instant OLD = NOW.minus(Duration.ofDays(2));
  private static final Instant RECENT = NOW.minus(Duration.ofHours(1));

  private final Store storage = new Store();
  private final Set<String> referenced = new HashSet<>();
  private final List<String> metrics = new ArrayList<>();
  private final List<Integer> referenceQueries = new ArrayList<>();

  private ReconcileOrphanCvFilesService service(int pageSize, int scanLimit, int deleteLimit) {
    return new ReconcileOrphanCvFilesService(
        storage,
        keys -> {
          referenceQueries.add(keys.size());
          Set<String> found = new HashSet<>(keys);
          found.retainAll(referenced);
          return found;
        },
        new OrphanReconciliationPolicy(Duration.ofHours(24), pageSize, scanLimit, deleteLimit),
        (useCase, outcome) -> metrics.add(outcome),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void anOldObjectWithoutAVersionIsDeletedAndEverythingElseIsKept() {
    String orphan = put(OLD);
    String inUse = put(OLD);
    referenced.add(inUse);
    String uploading = put(RECENT);
    String foreign = "cv/no-es-de-la-aplicacion.pdf";
    storage.objects.put(foreign, OLD);

    var result = service(100, 1000, 10).execute();

    assertThat(storage.deleted).containsExactly(orphan);
    assertThat(storage.objects).containsKeys(inUse, uploading, foreign);
    assertThat(result).isEqualTo(new ReconcileOrphanCvFilesUseCase.Result(4, 1, 1, false));
    assertThat(metrics).containsExactly("deleted");
  }

  // Muchos huérfanos de golpe no es lo esperable: se frena sin borrar ninguno.
  @Test
  void tooManyOrphansInOnePassHaltsWithoutDeletingAny() {
    for (int i = 0; i < 4; i++) {
      put(OLD);
    }

    var result = service(100, 1000, 3).execute();

    assertThat(result.halted()).isTrue();
    assertThat(result.deleted()).isZero();
    assertThat(storage.deleted).isEmpty();
    assertThat(metrics).containsExactly("halted");
  }

  @Test
  void aCleanStorageDeletesNothing() {
    referenced.add(put(OLD));

    var result = service(100, 1000, 10).execute();

    assertThat(result).isEqualTo(new ReconcileOrphanCvFilesUseCase.Result(1, 0, 0, false));
    assertThat(metrics).containsExactly("clean");
  }

  // Una pasada examina hasta su límite y la siguiente sigue donde quedó; al acabar vuelve a
  // empezar.
  @Test
  void eachPassResumesWhereThePreviousOneStoppedAndThenStartsOver() {
    for (int i = 0; i < 7; i++) {
      referenced.add(put(OLD));
    }
    // El almacén lista en orden de clave, no en el de alta.
    List<String> keys = new ArrayList<>(storage.objects.keySet());
    var service = service(2, 4, 10);

    var first = service.execute();
    var second = service.execute();
    var third = service.execute();

    assertThat(first.scanned()).isEqualTo(4);
    assertThat(second.scanned()).isEqualTo(3);
    assertThat(third.scanned()).isEqualTo(4);
    assertThat(storage.startAfters)
        .containsExactly("", keys.get(1), keys.get(3), keys.get(5), "", keys.get(1));
  }

  // Las referencias se consultan por página, no por objeto (TD-039).
  @Test
  void referencesAreAskedOncePerPage() {
    for (int i = 0; i < 5; i++) {
      put(OLD);
    }

    service(5, 1000, 10).execute();

    assertThat(referenceQueries).containsExactly(5);
  }

  @Test
  void anUnavailableStorageFailsThePassSoItIsRetriedLater() {
    put(OLD);
    storage.down = true;
    var service = service(100, 1000, 10);

    assertThatThrownBy(service::execute).isInstanceOf(UpstreamUnavailableException.class);
    assertThat(metrics).containsExactly("unavailable");
    assertThat(storage.deleted).isEmpty();
  }

  @Test
  void thePolicyRefusesAGraceShorterThanAnHourAndIncoherentLimits() {
    Duration tenMinutes = Duration.ofMinutes(10);
    Duration day = Duration.ofHours(24);

    assertThatThrownBy(() -> new OrphanReconciliationPolicy(tenMinutes, 10, 100, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrphanReconciliationPolicy(day, 100, 10, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrphanReconciliationPolicy(day, 10, 100, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private String put(Instant at) {
    String key = StoredObjectKey.random(FilePurpose.CV).value();
    storage.objects.put(key, at);
    return key;
  }

  /** Un almacén en memoria que lista en orden de clave, como S3. */
  private static final class Store implements ObjectStoragePort {
    final TreeMap<String, Instant> objects = new TreeMap<>();
    final List<String> deleted = new ArrayList<>();
    final List<String> startAfters = new ArrayList<>();
    boolean down;

    @Override
    public ObjectPage list(FilePurpose purpose, String startAfter, int maxKeys) {
      if (down) {
        throw new UpstreamUnavailableException("storage", new IOException("caído"));
      }
      startAfters.add(startAfter);
      Map<String, Instant> tail =
          startAfter.isEmpty() ? objects : objects.tailMap(startAfter, false);
      List<ObjectPage.Entry> page = new ArrayList<>();
      for (var entry : tail.entrySet()) {
        if (page.size() == maxKeys) {
          break;
        }
        page.add(new ObjectPage.Entry(entry.getKey(), entry.getValue()));
      }
      return new ObjectPage(page, tail.size() > page.size());
    }

    @Override
    public void delete(StoredObjectKey key) {
      deleted.add(key.value());
      objects.remove(key.value());
    }

    @Override
    public StoredObjectKey put(FilePurpose purpose, byte[] content, FileType type) {
      throw new UnsupportedOperationException("la reconciliación no sube nada");
    }

    @Override
    public URI presignDownload(StoredObjectKey key, String displayName, FileType type) {
      throw new UnsupportedOperationException("la reconciliación no descarga nada");
    }

    @Override
    public boolean exists(StoredObjectKey key) {
      return objects.containsKey(key.value());
    }
  }
}
