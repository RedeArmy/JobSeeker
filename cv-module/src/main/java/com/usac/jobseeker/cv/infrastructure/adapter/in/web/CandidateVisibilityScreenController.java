package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityCommand;
import com.usac.jobseeker.cv.application.usecase.changeavailability.ChangeAvailabilityUseCase;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.ChangeDiscoverabilityCommand;
import com.usac.jobseeker.cv.application.usecase.changediscoverability.ChangeDiscoverabilityUseCase;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileQuery;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AddAvailabilityExclusionCommand;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ListAvailabilityExclusionsQuery;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.ManageAvailabilityExclusionsUseCase;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.RemoveAvailabilityExclusionCommand;
import com.usac.jobseeker.cv.domain.model.AvailabilityStatus;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * La pantalla de visibilidad del candidato (Thymeleaf + HTMX, ADR-0005): descubribilidad,
 * disponibilidad y lista de exclusión, con los mismos casos de uso y reglas que
 * `CandidateVisibilityController`. Mismo comportamiento HTMX que las demás pantallas del perfil: la
 * operación devuelve el fragmento `content` completo y un error de validación reemplaza solo la
 * zona de errores. La identidad sale de la sesión; el CSRF lo comprueba la cadena web (ADR-0020).
 */
@Controller
public class CandidateVisibilityScreenController {

  private static final String PAGE_VIEW = "cv/candidate-visibility";
  private static final String CONTENT_FRAGMENT = "cv/candidate-visibility :: content";
  private static final String ERRORS_FRAGMENT = "cv/candidate-visibility :: errors";
  private static final String BASE = "/app/candidate-visibility";
  private static final String NOTICE = "notice";

  private final GetMyCandidateProfileUseCase getProfile;
  private final ChangeDiscoverabilityUseCase discoverability;
  private final ChangeAvailabilityUseCase availability;
  private final ManageAvailabilityExclusionsUseCase exclusions;

  public CandidateVisibilityScreenController(
      GetMyCandidateProfileUseCase getProfile,
      ChangeDiscoverabilityUseCase discoverability,
      ChangeAvailabilityUseCase availability,
      ManageAvailabilityExclusionsUseCase exclusions) {
    this.getProfile = getProfile;
    this.discoverability = discoverability;
    this.availability = availability;
    this.exclusions = exclusions;
  }

  record DiscoverabilityForm(Boolean discoverable, Long version) {}

  record AvailabilityForm(AvailabilityStatus status, Long version) {}

  record ExclusionForm(String accountId) {}

  @GetMapping(BASE)
  String page(Principal principal, Model model) {
    fill(model, caller(principal));
    return PAGE_VIEW;
  }

  @PutMapping(BASE + "/discoverability")
  String changeDiscoverability(
      Principal principal, Model model, @ModelAttribute DiscoverabilityForm f) {
    AccountId account = caller(principal);
    discoverability.execute(
        new ChangeDiscoverabilityCommand(
            account, Boolean.TRUE.equals(f.discoverable()), version(f.version())));
    return content(model, account);
  }

  @PutMapping(BASE + "/availability")
  String changeAvailability(Principal principal, Model model, @ModelAttribute AvailabilityForm f) {
    AccountId account = caller(principal);
    availability.execute(new ChangeAvailabilityCommand(account, f.status(), version(f.version())));
    return content(model, account);
  }

  @PostMapping(BASE + "/exclusions")
  String addExclusion(Principal principal, Model model, @ModelAttribute ExclusionForm f) {
    AccountId account = caller(principal);
    exclusions.add(new AddAvailabilityExclusionCommand(account, accountOf(f.accountId())));
    return content(model, account);
  }

  @DeleteMapping(BASE + "/exclusions/{accountId}")
  String removeExclusion(Principal principal, Model model, @PathVariable String accountId) {
    AccountId account = caller(principal);
    exclusions.remove(new RemoveAvailabilityExclusionCommand(account, accountOf(accountId)));
    return content(model, account);
  }

  // ---- errores: HTML en vez del sobre JSON de la API ----

  @ExceptionHandler(ValidationFailedException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
  String invalid(ValidationFailedException failure, Model model) {
    model.addAttribute("errors", CandidateMessages.describe(failure.violations()));
    return ERRORS_FRAGMENT;
  }

  /** 409 y el contenido recargado: quien editó con una versión vieja necesita la vigente. */
  @ExceptionHandler(StaleStateException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  String stale(Principal principal, Model model) {
    fill(model, caller(principal));
    model.addAttribute(
        NOTICE,
        "Tu perfil cambió desde que lo abriste, en otra pestaña o dispositivo. Lo recargamos: revisa y repite tu cambio.");
    return CONTENT_FRAGMENT;
  }

  /** 409 y el contenido recargado: esa cuenta ya estaba en la lista. */
  @ExceptionHandler(AlreadyExistsException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  String duplicate(Principal principal, Model model) {
    fill(model, caller(principal));
    model.addAttribute(NOTICE, "Esa cuenta ya estaba en tu lista.");
    return CONTENT_FRAGMENT;
  }

  /** 404 y el contenido recargado: la cuenta no existe, no estaba en la lista o no hay perfil. */
  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  String notFound(Principal principal, Model model) {
    fill(model, caller(principal));
    model.addAttribute(NOTICE, "No encontramos esa cuenta o ya no estaba en tu lista.");
    return CONTENT_FRAGMENT;
  }

  /** Sin la capacidad de candidato no hay pantalla: el HTML lo dice sin ofrecer los formularios. */
  @ExceptionHandler(ForbiddenCapabilityException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  String forbidden() {
    return "cv/candidate-forbidden";
  }

  // ---- auxiliares ----

  private String content(Model model, AccountId account) {
    fill(model, account);
    return CONTENT_FRAGMENT;
  }

  // Sin perfil de candidato no hay lista que leer: la página lo explica en lugar de fallar.
  private void fill(Model model, AccountId account) {
    Optional<CandidateProfileView> view =
        getProfile.execute(new GetMyCandidateProfileQuery(account));
    model.addAttribute("profile", CandidateProfileResponse.from(view));
    model.addAttribute(
        "exclusions",
        view.isEmpty()
            ? List.of()
            : AvailabilityExclusionsResponse.from(
                    exclusions.list(new ListAvailabilityExclusionsQuery(account)))
                .exclusions());
    model.addAttribute("statuses", AvailabilityStatus.values());
  }

  private static AccountId accountOf(String raw) {
    try {
      return new AccountId(UUID.fromString(raw == null ? "" : raw.strip()));
    } catch (IllegalArgumentException _) {
      throw new ValidationFailedException(List.of(new FieldViolation("accountId", "INVALID")));
    }
  }

  private static long version(Long value) {
    if (value == null) {
      throw new ValidationFailedException(List.of(new FieldViolation("version", "REQUIRED")));
    }
    return value;
  }

  private static AccountId caller(Principal principal) {
    return new AccountId(UUID.fromString(principal.getName()));
  }
}
