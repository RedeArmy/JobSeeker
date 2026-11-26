package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.matching.domain.model.ApplicationProfileSnapshot;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.ScoreSnapshot;
import com.usac.jobseeker.matching.domain.model.StoredScore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Almacén en memoria del puntaje para las pruebas de los casos de uso: reproduce las reglas que el
 * adaptador real garantiza (un par es único, el motivo más fuerte se conserva) y guarda lo que se
 * le pide para que la prueba lo inspeccione.
 */
public final class InMemoryScoreStore implements ScoreStorePort {

  public final Map<String, StoredScore> current = new LinkedHashMap<>();
  public final List<ScoreSnapshot> snapshots = new ArrayList<>();
  public final List<ApplicationProfileSnapshot> applicationSnapshots = new ArrayList<>();
  public final List<CandidateProfileId> anonymized = new ArrayList<>();
  public final List<String> deleted = new ArrayList<>();
  public final List<String> touched = new ArrayList<>();
  public int upsertCalls;
  public int purged;
  public Instant purgedBefore;

  private static String key(CandidateProfileId profile, JobPostingId job) {
    return profile.value() + ":" + job.value();
  }

  public void put(StoredScore stored) {
    current.put(key(stored.profile(), stored.job()), stored);
  }

  @Override
  public void upsertCurrent(Collection<StoredScore> scores) {
    upsertCalls++;
    for (StoredScore incoming : scores) {
      String key = key(incoming.profile(), incoming.job());
      StoredScore existing = current.get(key);
      ScoreReason reason =
          existing != null && existing.reason().ordinal() > incoming.reason().ordinal()
              ? existing.reason()
              : incoming.reason();
      current.put(
          key,
          new StoredScore(
              incoming.profile(), incoming.job(), incoming.score(), reason, incoming.computedAt()));
    }
  }

  @Override
  public Optional<StoredScore> findCurrent(CandidateProfileId profile, JobPostingId job) {
    return Optional.ofNullable(current.get(key(profile, job)));
  }

  @Override
  public List<JobPostingId> jobsOfProfile(CandidateProfileId profile, int limit) {
    return current.values().stream()
        .filter(s -> s.profile().equals(profile))
        .sorted(Comparator.comparing(StoredScore::computedAt).reversed())
        .limit(limit)
        .map(StoredScore::job)
        .toList();
  }

  @Override
  public List<CandidateProfileId> profilesOfJob(JobPostingId job, int limit) {
    return current.values().stream()
        .filter(s -> s.job().equals(job))
        .sorted(Comparator.comparing(StoredScore::computedAt).reversed())
        .limit(limit)
        .map(StoredScore::profile)
        .toList();
  }

  @Override
  public List<StoredScore> staleVersion(String currentVersion, int limit) {
    return current.values().stream()
        .filter(s -> !s.score().version().value().equals(currentVersion))
        .sorted(Comparator.comparing(StoredScore::computedAt))
        .limit(limit)
        .toList();
  }

  @Override
  public void deleteCurrent(CandidateProfileId profile, JobPostingId job) {
    deleted.add(key(profile, job));
    current.remove(key(profile, job));
  }

  @Override
  public void touch(CandidateProfileId profile, JobPostingId job, Instant at) {
    touched.add(key(profile, job));
    StoredScore existing = current.get(key(profile, job));
    if (existing != null) {
      current.put(
          key(profile, job),
          new StoredScore(profile, job, existing.score(), existing.reason(), at));
    }
  }

  @Override
  public void insertSnapshot(ScoreSnapshot snapshot) {
    snapshots.add(snapshot);
  }

  @Override
  public void insertApplicationSnapshot(ApplicationProfileSnapshot snapshot) {
    boolean duplicate =
        snapshot.applicationId() != null
            && applicationSnapshots.stream()
                .anyMatch(s -> snapshot.applicationId().equals(s.applicationId()));
    if (!duplicate) {
      applicationSnapshots.add(snapshot);
    }
  }

  @Override
  public void anonymizeProfile(CandidateProfileId profile, Instant at) {
    anonymized.add(profile);
  }

  @Override
  public int purgeSnapshotsOlderThan(Instant before) {
    purgedBefore = before;
    return purged;
  }
}
