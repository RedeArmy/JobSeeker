package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.ScoringModel;
import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.Weights;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dobles y construcciones compartidos por las pruebas de los casos de uso que puntúan pares: un
 * mundo en memoria con candidatos, vacantes y vectores, y los puertos que lo leen.
 */
public final class PairFixtures {

  public static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
  public static final String MODEL = "modelo-a";

  public final Map<AccountId, CandidateSignals> candidates = new HashMap<>();
  public final Map<JobPostingId, JobSignals> jobs = new HashMap<>();
  public final Map<String, Double> cosines = new HashMap<>();
  public final Map<CandidateProfileId, AccountId> accounts = new HashMap<>();
  public final InMemoryScoreStore store = new InMemoryScoreStore();
  public final List<String> metrics = new ArrayList<>();
  public final ScoringModel scoring =
      new ScoringModel(Weights.defaults(), Calibration.uncalibrated(MODEL));
  public final ScoreCalculator calculator =
      new ScoreCalculator(scoring, Clock.fixed(NOW, ZoneOffset.UTC));
  public int candidateReads;
  public int jobReads;

  public final ScoringSources sources =
      new ScoringSources(
          new com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader() {
            public Optional<CandidateSignals> read(AccountId account) {
              candidateReads++;
              return Optional.ofNullable(candidates.get(account));
            }

            @Override
            public Map<AccountId, CandidateSignals> readAll(Collection<AccountId> ids) {
              candidateReads++;
              Map<AccountId, CandidateSignals> found = new HashMap<>();
              ids.forEach(
                  id -> Optional.ofNullable(candidates.get(id)).ifPresent(s -> found.put(id, s)));
              return found;
            }
          },
          id -> {
            jobReads++;
            return Optional.ofNullable(jobs.get(id));
          },
          new PairSimilarityPort() {
            public Optional<Double> cosine(CandidateProfileId profile, JobPostingId job) {
              return Optional.ofNullable(cosines.get(profile.value() + ":" + job.value()));
            }

            public Map<CandidateProfileId, Double> cosinesForJob(
                JobPostingId job, Collection<CandidateProfileId> profiles) {
              Map<CandidateProfileId, Double> found = new HashMap<>();
              profiles.forEach(p -> cosine(p, job).ifPresent(c -> found.put(p, c)));
              return found;
            }

            public Map<JobPostingId, Double> cosinesForProfile(
                CandidateProfileId profile, Collection<JobPostingId> ids) {
              Map<JobPostingId, Double> found = new HashMap<>();
              ids.forEach(j -> cosine(profile, j).ifPresent(c -> found.put(j, c)));
              return found;
            }
          },
          new ProfileDirectoryPort() {
            public Optional<AccountId> accountOf(CandidateProfileId profile) {
              return Optional.ofNullable(accounts.get(profile));
            }

            public Map<CandidateProfileId, AccountId> accountsOf(
                Collection<CandidateProfileId> ids) {
              Map<CandidateProfileId, AccountId> found = new HashMap<>();
              ids.forEach(
                  id -> Optional.ofNullable(accounts.get(id)).ifPresent(a -> found.put(id, a)));
              return found;
            }

            public Optional<CandidateProfileId> profileOf(AccountId account) {
              return accounts.entrySet().stream()
                  .filter(e -> e.getValue().equals(account))
                  .map(Map.Entry::getKey)
                  .findFirst();
            }
          },
          store);

  /** Un candidato completo (declara Java) conocido por el mundo, con su cuenta enlazada. */
  public CandidateSignals candidate(String skill) {
    AccountId account = new AccountId(UUID.randomUUID());
    CandidateProfileId profile = new CandidateProfileId(UUID.randomUUID());
    CandidateSignals signals =
        new CandidateSignals(
            profile,
            account,
            true,
            "Backend",
            "GT",
            null,
            null,
            false,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new CandidateSignals.Skill(skill, 3)),
            List.of(),
            List.of());
    candidates.put(account, signals);
    accounts.put(profile, account);
    return signals;
  }

  /** Una vacante que pide Java, en el estado dado. */
  public JobSignals job(String status) {
    JobPostingId id = new JobPostingId(UUID.randomUUID());
    JobSignals signals =
        new JobSignals(
            id,
            1,
            "PUBLISHED".equals(status),
            false,
            status,
            "Backend",
            "Descripción",
            null,
            null,
            null,
            List.of(
                new JobSignals.Requirement("SKILL", true, "Java", null, null, null, null, null)));
    jobs.put(id, signals);
    return signals;
  }

  public void similar(CandidateSignals candidate, JobSignals job, double cosine) {
    cosines.put(candidate.profileId().value() + ":" + job.jobId().value(), cosine);
  }
}
