package com.usac.jobseeker.tracker.application.support;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** El título y el estado de las vacantes, según `job`, también de las eliminadas (BR-JOB-09). */
public interface PostingSummariesPort {

  record Summary(String title, String status) {}

  /** Las vacantes pedidas que existen, por identificador, en una sola lectura. */
  Map<UUID, Summary> of(Collection<UUID> jobPostingIds);
}
