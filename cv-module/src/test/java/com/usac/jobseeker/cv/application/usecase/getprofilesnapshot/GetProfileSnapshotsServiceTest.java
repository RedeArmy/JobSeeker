package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileReadPort;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfileCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile.CandidateCapabilityPort;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfessionalIdentity;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfile;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TD-039: leer varias cuentas con pocas consultas da **lo mismo** que leer de a una, en el orden
 * pedido y con las mismas reglas (sin capacidad, sin perfil o anonimizada no es legible), y cada
 * puerto se consulta una vez por tanda y no una por cuenta.
 */
class GetProfileSnapshotsServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  private final Set<AccountId> candidates = new HashSet<>();
  private final Map<AccountId, ProfessionalProfile> professionalOf = new HashMap<>();
  private final Map<AccountId, CandidateProfile> candidateOf = new HashMap<>();
  private final Map<ProfessionalProfileId, ProfileCollections> itemsOf = new HashMap<>();
  private final Map<CandidateProfileId, CandidateCollections> setsOf = new HashMap<>();
  private final List<String> metrics = new ArrayList<>();
  private final List<String> calls = new ArrayList<>();

  private final GetProfileSnapshotService service =
      new GetProfileSnapshotService(
          new CandidateCapabilityPort() {
            public boolean isCandidate(AccountId id) {
              calls.add("capability:one");
              return candidates.contains(id);
            }

            public Set<AccountId> candidatesAmong(Collection<AccountId> ids) {
              calls.add("capability:batch");
              Set<AccountId> found = new HashSet<>(ids);
              found.retainAll(candidates);
              return found;
            }
          },
          new ProfessionalProfileReadPort() {
            public Optional<ProfessionalProfile> findByAccountId(AccountId id) {
              calls.add("professional:one");
              return Optional.ofNullable(professionalOf.get(id));
            }

            public Map<AccountId, ProfessionalProfile> findAllByAccountIds(
                Collection<AccountId> ids) {
              calls.add("professional:batch");
              Map<AccountId, ProfessionalProfile> found = new HashMap<>(professionalOf);
              found.keySet().retainAll(ids);
              return found;
            }
          },
          new ProfileCollectionsReadPort() {
            public ProfileCollections findByProfileId(ProfessionalProfileId id) {
              calls.add("items:one");
              return itemsOf.getOrDefault(id, ProfileCollections.empty());
            }

            public Map<ProfessionalProfileId, ProfileCollections> findAllByProfileIds(
                Collection<ProfessionalProfileId> ids) {
              calls.add("items:batch");
              Map<ProfessionalProfileId, ProfileCollections> found = new HashMap<>();
              ids.forEach(
                  id -> found.put(id, itemsOf.getOrDefault(id, ProfileCollections.empty())));
              return found;
            }
          },
          new CandidateProfileReadPort() {
            public Optional<CandidateProfile> findByAccountId(AccountId id) {
              calls.add("candidate:one");
              return Optional.ofNullable(candidateOf.get(id));
            }

            public Map<AccountId, CandidateProfile> findAllByAccountIds(Collection<AccountId> ids) {
              calls.add("candidate:batch");
              Map<AccountId, CandidateProfile> found = new HashMap<>(candidateOf);
              found.keySet().retainAll(ids);
              return found;
            }
          },
          new CandidateCollectionsReadPort() {
            public CandidateCollections findByProfileId(CandidateProfileId id) {
              calls.add("sets:one");
              return setsOf.getOrDefault(id, CandidateCollections.empty());
            }

            public Map<CandidateProfileId, CandidateCollections> findAllByProfileIds(
                Collection<CandidateProfileId> ids) {
              calls.add("sets:batch");
              Map<CandidateProfileId, CandidateCollections> found = new HashMap<>();
              ids.forEach(
                  id -> found.put(id, setsOf.getOrDefault(id, CandidateCollections.empty())));
              return found;
            }
          },
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome));

  /** Una cuenta candidata con perfiles y un puesto y una habilidad que la distinguen. */
  private AccountId candidate(String title, String skill) {
    AccountId account = new AccountId(UUID.randomUUID());
    candidates.add(account);
    var professional =
        new ProfessionalProfile(
            new ProfessionalProfileId(UUID.randomUUID()),
            account,
            ProfessionalIdentity.of("Nombre " + title, title, null),
            1,
            T0);
    professionalOf.put(account, professional);
    var candidateProfile =
        new CandidateProfile(
            new CandidateProfileId(UUID.randomUUID()),
            account,
            CandidateDetails.of("x@y.co", null, "gt", null, null, false, null, null, null, null),
            false,
            AvailabilityStatus.UNSET,
            1,
            T0);
    candidateOf.put(account, candidateProfile);
    itemsOf.put(
        professional.id(),
        new ProfileCollections(
            List.of(
                Experience.of(
                    new ProfileItemId(UUID.randomUUID()),
                    "Empresa",
                    title,
                    LocalDate.of(2020, 1, 1),
                    null,
                    "texto")),
            List.of(),
            List.of(),
            List.of()));
    setsOf.put(
        candidateProfile.id(),
        new CandidateCollections(List.of(new CandidateSkill(skill, 3)), List.of(), List.of()));
    return account;
  }

  private List<ProfileSnapshot> batch(AccountId... accounts) {
    return service.execute(new GetProfileSnapshotsQuery(List.of(accounts)));
  }

  @Test
  void eachAccountIsReadWithTheSameResultAsTheSingleRead() {
    var a = candidate("Backend", "Java");
    var b = candidate("Frontend", "React");

    var snapshots = batch(a, b);

    assertThat(snapshots).hasSize(2);
    assertThat(snapshots.get(0))
        .isEqualTo(service.execute(new GetProfileSnapshotQuery(a)).orElseThrow());
    assertThat(snapshots.get(1))
        .isEqualTo(service.execute(new GetProfileSnapshotQuery(b)).orElseThrow());
  }

  @Test
  void theResultKeepsTheRequestedOrderAndEveryProfileKeepsItsOwnItems() {
    var a = candidate("Backend", "Java");
    var b = candidate("Frontend", "React");
    var c = candidate("Datos", "SQL");

    var snapshots = batch(c, a, b);

    assertThat(snapshots)
        .extracting(ProfileSnapshot::headline)
        .containsExactly("Datos", "Backend", "Frontend");
    assertThat(snapshots.get(0).skills())
        .extracting(ProfileSnapshot.Skill::name)
        .containsExactly("SQL");
    assertThat(snapshots.get(1).experiences())
        .singleElement()
        .satisfies(e -> assertThat(e.title()).isEqualTo("Backend"));
  }

  // Las mismas reglas que la individual: sin capacidad, sin un perfil o desconocida no es legible.
  @Test
  void anAccountWithoutTheCapabilityOrWithoutAProfileIsSimplyAbsent() {
    var readable = candidate("Backend", "Java");
    var noCapability = candidate("Otro", "Go");
    candidates.remove(noCapability);
    var noCandidateProfile = candidate("Medio", "C");
    candidateOf.remove(noCandidateProfile);
    var noProfessional = candidate("Medio2", "C");
    professionalOf.remove(noProfessional);
    var unknown = new AccountId(UUID.randomUUID());

    var snapshots = batch(noCapability, readable, noCandidateProfile, noProfessional, unknown);

    assertThat(snapshots).extracting(s -> s.accountId()).containsExactly(readable.value());
  }

  // La razón de TD-039: un puerto se consulta una vez por tanda, no una por cuenta.
  @Test
  void eachPortIsAskedOncePerBatchAndNotOncePerAccount() {
    var accounts = new ArrayList<AccountId>();
    for (int i = 0; i < 25; i++) {
      accounts.add(candidate("Puesto " + i, "Skill " + i));
    }
    calls.clear();

    service.execute(new GetProfileSnapshotsQuery(accounts));

    assertThat(calls)
        .containsExactly(
            "capability:batch",
            "professional:batch",
            "candidate:batch",
            "items:batch",
            "sets:batch");
    assertThat(metrics).containsExactly("get_profile_snapshot:batch");
  }

  @Test
  void anEmptyBatchReadsNothing() {
    calls.clear();

    assertThat(batch()).isEmpty();

    assertThat(calls).containsExactly("capability:batch");
  }

  @Test
  void whenNobodyIsACandidateNothingElseIsRead() {
    var notCandidate = candidate("Backend", "Java");
    candidates.clear();
    calls.clear();

    assertThat(batch(notCandidate)).isEmpty();

    assertThat(calls).containsExactly("capability:batch");
  }

  @Test
  void theQueryRefusesMoreAccountsThanTheLimit() {
    var many = new ArrayList<AccountId>();
    for (int i = 0; i <= GetProfileSnapshotsQuery.MAX_ACCOUNTS; i++) {
      many.add(new AccountId(UUID.randomUUID()));
    }

    assertThatThrownBy(() -> new GetProfileSnapshotsQuery(many))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GetProfileSnapshotsQuery(null))
        .isInstanceOf(NullPointerException.class);
  }

  // La lectura individual es el caso de una cuenta: usa los mismos puertos por lotes.
  @Test
  void theSingleReadGoesThroughTheSameBatchPorts() {
    var a = candidate("Backend", "Java");
    calls.clear();

    assertThat(service.execute(new GetProfileSnapshotQuery(a))).isPresent();

    assertThat(calls)
        .containsExactly(
            "capability:batch",
            "professional:batch",
            "candidate:batch",
            "items:batch",
            "sets:batch");
    assertThat(metrics).containsExactly("get_profile_snapshot:found");
  }
}
