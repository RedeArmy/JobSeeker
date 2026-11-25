package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EmbeddingMeta;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Doble en memoria del almacén de vectores de perfiles, con la lápida de la anonimización. */
final class InMemoryEmbeddingStore implements EmbeddingStorePort {

  final Map<CandidateProfileId, EntityEmbedding> embeddings = new HashMap<>();
  final Map<CandidateProfileId, Boolean> discoverable = new HashMap<>();
  final Map<AccountId, CandidateProfileId> accounts = new HashMap<>();
  final Set<AccountId> buried = new HashSet<>();
  final List<String> operations = new ArrayList<>();

  /** Si es verdadero, la anonimización llega justo antes de guardar el vector (carrera). */
  boolean buryDuringSave;

  private boolean isBuried(CandidateProfileId id) {
    return accounts.entrySet().stream()
        .anyMatch(e -> e.getValue().equals(id) && buried.contains(e.getKey()));
  }

  @Override
  public Optional<ProfileEmbeddingState> findProfileState(CandidateProfileId id) {
    if (!discoverable.containsKey(id)) {
      return Optional.empty();
    }
    return Optional.of(
        new ProfileEmbeddingState(
            discoverable.get(id),
            Optional.ofNullable(embeddings.get(id))
                .map(
                    e ->
                        new EmbeddingMeta(
                            e.model(), e.contentHash(), e.truncated(), e.computedAt()))));
  }

  @Override
  public Optional<EntityEmbedding> findProfileEmbedding(CandidateProfileId id) {
    return Optional.ofNullable(embeddings.get(id));
  }

  @Override
  public boolean saveProfileEmbedding(
      CandidateProfileId id, boolean discoverableIfNew, EntityEmbedding embedding) {
    operations.add("save");
    if (buryDuringSave) {
      accounts.forEach((account, profile) -> buried.add(account));
    }
    if (isBuried(id)) {
      return false;
    }
    embeddings.put(id, embedding);
    discoverable.putIfAbsent(id, discoverableIfNew);
    return true;
  }

  @Override
  public boolean setProfileDiscoverable(CandidateProfileId id, boolean flag) {
    operations.add("discoverable=" + flag);
    if (isBuried(id)) {
      return false;
    }
    discoverable.put(id, flag);
    return true;
  }

  @Override
  public boolean registerProfile(AccountId accountId, CandidateProfileId id) {
    operations.add("register");
    accounts.putIfAbsent(accountId, id);
    return !buried.contains(accountId);
  }

  @Override
  public Optional<CandidateProfileId> findProfileIdByAccount(AccountId accountId) {
    return buried.contains(accountId)
        ? Optional.empty()
        : Optional.ofNullable(accounts.get(accountId));
  }

  @Override
  public boolean anonymizeProfile(AccountId accountId) {
    operations.add("anonymize");
    boolean fresh = buried.add(accountId);
    CandidateProfileId id = accounts.get(accountId);
    boolean cleared = id != null && embeddings.remove(id) != null;
    if (id != null) {
      discoverable.put(id, false);
    }
    return fresh || cleared;
  }

  @Override
  public boolean clearProfileEmbedding(CandidateProfileId id) {
    return embeddings.remove(id) != null;
  }

  // ---- vacantes ----

  /** Las copias que guarda `matching` de cada vacante: {publicada, en revisión}. */
  final Map<JobPostingId, boolean[]> jobCopies = new HashMap<>();

  final Map<JobPostingId, EntityEmbedding> jobEmbeddings = new HashMap<>();

  @Override
  public Optional<JobEmbeddingState> findJobState(JobPostingId id) {
    boolean[] copies = jobCopies.get(id);
    if (copies == null) {
      return Optional.empty();
    }
    return Optional.of(
        new JobEmbeddingState(
            copies[0],
            copies[1],
            Optional.ofNullable(jobEmbeddings.get(id))
                .map(
                    e ->
                        new EmbeddingMeta(
                            e.model(), e.contentHash(), e.truncated(), e.computedAt()))));
  }

  @Override
  public Optional<EntityEmbedding> findJobEmbedding(JobPostingId id) {
    return Optional.ofNullable(jobEmbeddings.get(id));
  }

  @Override
  public void saveJobEmbedding(JobPostingId id, boolean publishedIfNew, EntityEmbedding embedding) {
    operations.add("save-job");
    jobCopies.computeIfAbsent(id, k -> new boolean[] {publishedIfNew, false});
    jobEmbeddings.put(id, embedding);
  }

  @Override
  public void setJobCopies(JobPostingId id, boolean published, boolean reviewing) {
    operations.add("copies-job");
    jobCopies.put(id, new boolean[] {published, reviewing});
  }
}
