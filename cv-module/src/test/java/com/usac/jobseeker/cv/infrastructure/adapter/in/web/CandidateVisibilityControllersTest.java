package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityCommand;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.ChangeDiscoverabilityCommand;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AddAvailabilityExclusionCommand;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ExclusionView;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ListAvailabilityExclusionsQuery;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ManageAvailabilityExclusionsUseCase;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.RemoveAvailabilityExclusionCommand;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import com.usac.jobseeker.cv.domain.model.CandidateCollections;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ConcurrentModel;

/** Los dos adaptadores solo traducen: la identidad sale del token o la sesión, nunca del cuerpo. */
@SuppressWarnings("unchecked")
class CandidateVisibilityControllersTest {

  private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");

  private final UUID caller = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final Principal principal = caller::toString;
  private final List<Object> commands = new ArrayList<>();
  private Optional<CandidateProfileView> stored = Optional.empty();

  private final CandidateProfileView view =
      new CandidateProfileView(
          UUID.randomUUID(),
          CandidateDetails.of("a@b.co", null, null, null, null, null, null, null, null, null),
          true,
          AvailabilityStatus.UNSET,
          4,
          T0,
          CandidateCollections.empty());

  private final ManageAvailabilityExclusionsUseCase exclusionsUseCase =
      new ManageAvailabilityExclusionsUseCase() {
        public List<ExclusionView> list(ListAvailabilityExclusionsQuery q) {
          commands.add(q);
          return List.of(new ExclusionView(other, T0));
        }

        public List<ExclusionView> add(AddAvailabilityExclusionCommand c) {
          commands.add(c);
          return List.of(new ExclusionView(other, T0));
        }

        public List<ExclusionView> remove(RemoveAvailabilityExclusionCommand c) {
          commands.add(c);
          return List.of();
        }
      };

  private final CandidateVisibilityController api =
      new CandidateVisibilityController(
          c -> {
            commands.add(c);
            return view;
          },
          c -> {
            commands.add(c);
            return view;
          },
          exclusionsUseCase);

  private final CandidateVisibilityScreenController screen =
      new CandidateVisibilityScreenController(
          q -> stored,
          c -> {
            commands.add(c);
            return view;
          },
          c -> {
            commands.add(c);
            return view;
          },
          exclusionsUseCase);

  // ---- API JSON ----

  @Test
  void theApiPassesTheCallerAndEveryFieldToItsCommand() {
    api.changeDiscoverability(principal, new VisibilityRequests.Discoverability(false, 4L));
    api.changeAvailability(
        principal, new VisibilityRequests.Availability(AvailabilityStatus.OPEN_TO_WORK, 5L));

    assertThat(commands)
        .containsExactly(
            new ChangeDiscoverabilityCommand(new AccountId(caller), false, 4),
            new ChangeAvailabilityCommand(
                new AccountId(caller), AvailabilityStatus.OPEN_TO_WORK, 5));
  }

  @Test
  void theApiExclusionRoutesReturnTheListAndUseTheCallerAsOwner() {
    var added = api.addExclusion(principal, new VisibilityRequests.Exclusion(" " + other + " "));
    var listed = api.listExclusions(principal);
    var removed = api.removeExclusion(principal, other.toString());

    assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(added.getBody().exclusions())
        .extracting(AvailabilityExclusionsResponse.Item::accountId)
        .containsExactly(other.toString());
    assertThat(listed.exclusions()).hasSize(1);
    assertThat(listed.exclusions().get(0).createdAt()).isEqualTo(T0.toString());
    assertThat(removed.exclusions()).isEmpty();
    assertThat(commands)
        .containsExactly(
            new AddAvailabilityExclusionCommand(new AccountId(caller), new AccountId(other)),
            new ListAvailabilityExclusionsQuery(new AccountId(caller)),
            new RemoveAvailabilityExclusionCommand(new AccountId(caller), new AccountId(other)));
  }

  @Test
  void theApiRejectsAMissingFlagVersionOrBadAccountBeforeAnyUseCase() {
    var noFlag = new VisibilityRequests.Discoverability(null, 1L);
    var noVersion = new VisibilityRequests.Availability(AvailabilityStatus.UNSET, null);
    var garbage = new VisibilityRequests.Exclusion("no-es-uuid");
    var blank = new VisibilityRequests.Exclusion(null);

    assertThatThrownBy(() -> api.changeDiscoverability(principal, noFlag))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> api.changeAvailability(principal, noVersion))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> api.addExclusion(principal, garbage))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> api.addExclusion(principal, blank))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> api.removeExclusion(principal, "x"))
        .isInstanceOf(ValidationFailedException.class);

    assertThat(commands).isEmpty();
  }

  // ---- pantalla ----

  @Test
  void thePageWithoutAProfileHasNoListAndWithOneLoadsTheList() {
    var empty = new ConcurrentModel();
    var filled = new ConcurrentModel();

    assertThat(screen.page(principal, empty)).isEqualTo("cv/candidate-visibility");
    stored = Optional.of(view);
    screen.page(principal, filled);

    assertThat((List<?>) empty.getAttribute("exclusions")).isEmpty();
    assertThat((CandidateProfileResponse) empty.getAttribute("profile"))
        .extracting(CandidateProfileResponse::created)
        .isEqualTo(false);
    assertThat((List<?>) filled.getAttribute("exclusions")).hasSize(1);
    assertThat((AvailabilityStatus[]) filled.getAttribute("statuses"))
        .contains(AvailabilityStatus.OPEN_TO_WORK);
  }

  @Test
  void everyScreenOperationReturnsTheWholeContentAfterTheRightCommand() {
    stored = Optional.of(view);
    var model = new ConcurrentModel();

    var results =
        List.of(
            screen.changeDiscoverability(
                principal,
                model,
                new CandidateVisibilityScreenController.DiscoverabilityForm(null, 4L)),
            screen.changeAvailability(
                principal,
                model,
                new CandidateVisibilityScreenController.AvailabilityForm(
                    AvailabilityStatus.NOT_LOOKING, 4L)),
            screen.addExclusion(
                principal,
                model,
                new CandidateVisibilityScreenController.ExclusionForm(other.toString())),
            screen.removeExclusion(principal, model, other.toString()));

    assertThat(results).containsOnly("cv/candidate-visibility :: content");
    assertThat(commands)
        .contains(
            new ChangeDiscoverabilityCommand(new AccountId(caller), false, 4),
            new ChangeAvailabilityCommand(new AccountId(caller), AvailabilityStatus.NOT_LOOKING, 4),
            new AddAvailabilityExclusionCommand(new AccountId(caller), new AccountId(other)),
            new RemoveAvailabilityExclusionCommand(new AccountId(caller), new AccountId(other)));
  }

  @Test
  void theScreenRejectsAMissingVersionOrABadAccount() {
    var model = new ConcurrentModel();
    var noVersion = new CandidateVisibilityScreenController.DiscoverabilityForm(true, null);
    var badAccount = new CandidateVisibilityScreenController.ExclusionForm("no-es-uuid");

    assertThatThrownBy(() -> screen.changeDiscoverability(principal, model, noVersion))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> screen.addExclusion(principal, model, badAccount))
        .isInstanceOf(ValidationFailedException.class);
    assertThatThrownBy(() -> screen.removeExclusion(principal, model, "x"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void eachFailureBecomesItsOwnFragmentWithItsOwnStatusAndNotice() {
    stored = Optional.of(view);
    var invalid = new ConcurrentModel();
    var stale = new ConcurrentModel();
    var duplicate = new ConcurrentModel();
    var missing = new ConcurrentModel();

    var errors =
        screen.invalid(
            new ValidationFailedException(
                List.of(new FieldViolation("accountId", "SELF_EXCLUSION"))),
            invalid);
    var staleView = screen.stale(principal, stale);
    var duplicateView = screen.duplicate(principal, duplicate);
    var missingView = screen.notFound(principal, missing);

    assertThat(errors).isEqualTo("cv/candidate-visibility :: errors");
    assertThat((List<String>) invalid.getAttribute("errors"))
        .containsExactly("La cuenta no puede ser tu propia cuenta.");
    assertThat(List.of(staleView, duplicateView, missingView))
        .containsOnly("cv/candidate-visibility :: content");
    assertThat(stale.getAttribute("notice").toString()).contains("cambió");
    assertThat(duplicate.getAttribute("notice").toString()).contains("ya estaba");
    assertThat(missing.getAttribute("notice").toString()).contains("No encontramos");
    assertThat(screen.forbidden()).isEqualTo("cv/candidate-forbidden");
  }

  @Test
  void theFailuresThatReloadAreTheOnesTheDomainThrows() {
    assertThat(new StaleStateException()).isInstanceOf(RuntimeException.class);
    assertThat(new AlreadyExistsException()).isInstanceOf(RuntimeException.class);
    assertThat(new NotFoundException()).isInstanceOf(RuntimeException.class);
  }
}
