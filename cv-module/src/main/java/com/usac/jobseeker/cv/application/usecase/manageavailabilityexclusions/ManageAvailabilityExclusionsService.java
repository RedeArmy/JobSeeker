package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.support.CandidateChange;
import com.usac.jobseeker.cv.application.support.Stamp;
import com.usac.jobseeker.cv.domain.event.CandidateAvailabilityExclusionChanged;
import com.usac.jobseeker.cv.domain.model.AvailabilityExclusion;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La lista no es parte de los
 * datos del perfil: no avanza su versión ni se protege con ella; la unicidad por cuenta es su
 * control de concurrencia (BR-CAND-05). Ni el log ni la métrica llevan el identificador de la
 * cuenta excluida: es una relación entre personas (BR-DATA-01).
 */
public class ManageAvailabilityExclusionsService implements ManageAvailabilityExclusionsUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ManageAvailabilityExclusionsService.class);
  private static final String USE_CASE_LIST = "list_availability_exclusions";
  private static final String USE_CASE_ADD = "add_availability_exclusion";
  private static final String USE_CASE_REMOVE = "remove_availability_exclusion";

  private final AvailabilityExclusionStorePort exclusions;
  private final AccountDirectoryPort directory;
  private final CandidateChange change;
  // TD-014: el reloj llega con el resto del sello, como en el resto del módulo.
  private final Stamp stamp;
  private final UseCaseMetricsPort metrics;

  public ManageAvailabilityExclusionsService(
      AvailabilityExclusionStorePort exclusions,
      AccountDirectoryPort directory,
      CandidateChange change,
      Stamp stamp,
      UseCaseMetricsPort metrics) {
    this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.change = Objects.requireNonNull(change, "change");
    this.stamp = Objects.requireNonNull(stamp, "stamp");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExclusionView> list(ListAvailabilityExclusionsQuery query) {
    change.requireCandidate(USE_CASE_LIST, query.accountId());
    CandidateProfile profile = change.requireProfile(USE_CASE_LIST, query.accountId());
    metrics.count(USE_CASE_LIST, "listed");
    return views(profile);
  }

  @Override
  @Transactional
  public List<ExclusionView> add(AddAvailabilityExclusionCommand c) {
    change.requireCandidate(USE_CASE_ADD, c.accountId());
    CandidateProfile profile = change.requireProfile(USE_CASE_ADD, c.accountId());
    AvailabilityExclusion exclusion = validated(profile, c);
    if (!directory.isLive(c.excludedAccount())) {
      metrics.count(USE_CASE_ADD, "not_found");
      throw new NotFoundException();
    }
    if (!exclusions.insert(profile.id(), exclusion)) {
      metrics.count(USE_CASE_ADD, "duplicate");
      throw new AlreadyExistsException();
    }
    change.publishEvent(
        profile,
        CandidateAvailabilityExclusionChanged.EVENT_TYPE,
        CandidateAvailabilityExclusionChanged.payload(
            profile.accountId(), c.excludedAccount(), CandidateAvailabilityExclusionChanged.ADDED));
    metrics.count(USE_CASE_ADD, "added");
    LOG.info(
        "event=availability_exclusion_changed action=added Exclusión de disponibilidad añadida");
    return views(profile);
  }

  @Override
  @Transactional
  public List<ExclusionView> remove(RemoveAvailabilityExclusionCommand c) {
    change.requireCandidate(USE_CASE_REMOVE, c.accountId());
    CandidateProfile profile = change.requireProfile(USE_CASE_REMOVE, c.accountId());
    if (!exclusions.delete(profile.id(), c.excludedAccount())) {
      metrics.count(USE_CASE_REMOVE, "not_found");
      throw new NotFoundException();
    }
    change.publishEvent(
        profile,
        CandidateAvailabilityExclusionChanged.EVENT_TYPE,
        CandidateAvailabilityExclusionChanged.payload(
            profile.accountId(),
            c.excludedAccount(),
            CandidateAvailabilityExclusionChanged.REMOVED));
    metrics.count(USE_CASE_REMOVE, "removed");
    LOG.info(
        "event=availability_exclusion_changed action=removed Exclusión de disponibilidad retirada");
    return views(profile);
  }

  private AvailabilityExclusion validated(
      CandidateProfile profile, AddAvailabilityExclusionCommand c) {
    try {
      if (exclusions.count(profile.id()) >= AvailabilityExclusion.MAX_PER_CANDIDATE) {
        throw new ValidationFailedException(List.of(new FieldViolation("exclusions", "TOO_MANY")));
      }
      return AvailabilityExclusion.of(c.accountId(), c.excludedAccount(), stamp.clock().instant());
    } catch (ValidationFailedException invalid) {
      metrics.count(USE_CASE_ADD, "invalid");
      throw invalid;
    }
  }

  private List<ExclusionView> views(CandidateProfile profile) {
    return exclusions.list(profile.id()).stream().map(ExclusionView::of).toList();
  }
}
