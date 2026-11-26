package com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.ObjectPage;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). No escribe en la base: solo
 * la lee, así que no abre transacción.
 *
 * <p>Tres reglas de seguridad, porque un error aquí borra CVs reales:
 *
 * <ul>
 *   <li>Un objeto más joven que la espera no se juzga: puede ser una subida a medio confirmar.
 *   <li>Una clave con un formato que la aplicación no crea nunca se borra: no es nuestra.
 *   <li>Si una pasada encuentra más huérfanos que el freno, no borra ninguno y lo registra como
 *       error: lo esperable son unos pocos, y muchos de golpe indican otra cosa (por ejemplo una
 *       base restaurada desde una copia anterior al almacén), que una persona debe revisar.
 * </ul>
 */
public class ReconcileOrphanCvFilesService implements ReconcileOrphanCvFilesUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ReconcileOrphanCvFilesService.class);
  private static final String USE_CASE = "reconcile_orphan_cv_files";

  private final ObjectStoragePort storage;
  private final CvFileReferencePort references;
  private final OrphanReconciliationPolicy policy;
  private final UseCaseMetricsPort metrics;
  private final Clock clock;
  // Dónde siguió la última pasada; vuelve al principio al acabar el almacén. Por instancia: dos
  // instancias que recorran partes distintas solo adelantan el trabajo.
  private String resumeAfter = "";

  public ReconcileOrphanCvFilesService(
      ObjectStoragePort storage,
      CvFileReferencePort references,
      OrphanReconciliationPolicy policy,
      UseCaseMetricsPort metrics,
      Clock clock) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.references = Objects.requireNonNull(references, "references");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized Result execute() {
    Instant oldEnough = clock.instant().minus(policy.grace());
    List<StoredObjectKey> orphans = new ArrayList<>();
    int scanned = 0;
    String cursor = resumeAfter;
    boolean more = true;
    try {
      while (more && scanned < policy.scanLimit()) {
        ObjectPage page = storage.list(FilePurpose.CV, cursor, policy.pageSize());
        scanned += page.objects().size();
        orphans.addAll(orphansIn(page, oldEnough));
        cursor = page.lastKey();
        more = page.truncated() && !page.objects().isEmpty();
      }
    } catch (UpstreamUnavailableException e) {
      metrics.count(USE_CASE, "unavailable");
      throw e;
    }
    resumeAfter = more ? cursor : "";
    if (orphans.size() > policy.deleteLimit()) {
      metrics.count(USE_CASE, "halted");
      LOG.error(
          "event=orphan_cv_reconciliation_halted orphans={} limit={} Demasiados CV sin referencia"
              + " en una pasada: no se borró ninguno, revisar antes de seguir",
          orphans.size(),
          policy.deleteLimit());
      return new Result(scanned, orphans.size(), 0, true);
    }
    orphans.forEach(storage::delete);
    metrics.count(USE_CASE, orphans.isEmpty() ? "clean" : "deleted");
    LOG.info(
        "event=orphan_cv_reconciliation scanned={} deleted={} Reconciliación de CV huérfanos",
        scanned,
        orphans.size());
    return new Result(scanned, orphans.size(), orphans.size(), false);
  }

  // De una página, los objetos con edad suficiente, con formato propio y sin fila que los use.
  private List<StoredObjectKey> orphansIn(ObjectPage page, Instant oldEnough) {
    List<StoredObjectKey> candidates = new ArrayList<>();
    for (ObjectPage.Entry entry : page.objects()) {
      if (entry.lastModified().isAfter(oldEnough)) {
        continue;
      }
      try {
        candidates.add(new StoredObjectKey(entry.key()));
      } catch (IllegalArgumentException unknownFormat) {
        LOG.warn("event=orphan_cv_unknown_key Objeto con una clave que la aplicación no crea");
      }
    }
    if (candidates.isEmpty()) {
      return candidates;
    }
    Set<String> referenced =
        references.referenced(candidates.stream().map(StoredObjectKey::value).toList());
    return candidates.stream().filter(key -> !referenced.contains(key.value())).toList();
  }
}
