package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.StaleStateException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileQuery;
import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.GetMyCandidateProfileUseCase;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesCommand;
import com.usac.jobseeker.cv.application.usecase.replacecandidatelanguages.ReplaceCandidateLanguagesUseCase;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsCommand;
import com.usac.jobseeker.cv.application.usecase.replacecandidateskills.ReplaceCandidateSkillsUseCase;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsCommand;
import com.usac.jobseeker.cv.application.usecase.replaceworkauthorizations.ReplaceWorkAuthorizationsUseCase;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.SaveCandidateProfileCommand;
import com.usac.jobseeker.cv.application.usecase.savecandidateprofile.SaveCandidateProfileUseCase;
import com.usac.jobseeker.cv.domain.model.SalaryPeriod;
import com.usac.jobseeker.cv.domain.model.WorkModality;
import com.usac.jobseeker.cv.domain.model.WorkSchedule;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * La pantalla del perfil de candidato (Thymeleaf + HTMX, ADR-0005): los mismos casos de uso y
 * reglas que `CandidateProfileController`, devolviendo HTML. Igual que la del perfil profesional,
 * cada operación devuelve el fragmento `content` completo con la versión nueva, y un error de
 * validación reemplaza solo la zona de errores para no borrar lo tecleado.
 *
 * <p>La identidad sale de la sesión, nunca de un campo; el CSRF lo comprueba la cadena web.
 */
@Controller
public class CandidateScreenController {

  private static final String PAGE_VIEW = "cv/candidate-profile";
  private static final String CONTENT_FRAGMENT = "cv/candidate-profile :: content";
  private static final String ERRORS_FRAGMENT = "cv/candidate-profile :: errors";
  private static final String BASE = "/app/candidate-profile";

  private final GetMyCandidateProfileUseCase getProfile;
  private final SaveCandidateProfileUseCase saveProfile;
  private final ReplaceCandidateSkillsUseCase skills;
  private final ReplaceCandidateLanguagesUseCase languages;
  private final ReplaceWorkAuthorizationsUseCase authorizations;

  public CandidateScreenController(
      GetMyCandidateProfileUseCase getProfile,
      SaveCandidateProfileUseCase saveProfile,
      ReplaceCandidateSkillsUseCase skills,
      ReplaceCandidateLanguagesUseCase languages,
      ReplaceWorkAuthorizationsUseCase authorizations) {
    this.getProfile = getProfile;
    this.saveProfile = saveProfile;
    this.skills = skills;
    this.languages = languages;
    this.authorizations = authorizations;
  }

  /** Datos del formulario de contacto y elegibilidad. */
  record DetailsForm(
      String contactEmail,
      String contactPhone,
      String countryOfResidence,
      WorkModality desiredModality,
      WorkSchedule preferredWorkSchedule,
      Boolean relocationWillingness,
      BigDecimal salaryMin,
      BigDecimal salaryMax,
      String salaryCurrency,
      SalaryPeriod salaryPeriod,
      Long version) {}

  /** Un conjunto como texto, con la versión que se leyó. */
  record SetForm(String text, Long version) {}

  @GetMapping(BASE)
  String page(Principal principal, Model model) {
    fill(model, currentProfile(principal));
    return PAGE_VIEW;
  }

  @PutMapping(BASE + "/details")
  String saveDetails(Principal principal, Model model, @ModelAttribute DetailsForm f) {
    var result =
        saveProfile.execute(
            new SaveCandidateProfileCommand(
                caller(principal),
                f.contactEmail(),
                f.contactPhone(),
                f.countryOfResidence(),
                f.desiredModality(),
                f.preferredWorkSchedule(),
                f.relocationWillingness(),
                f.salaryMin(),
                f.salaryMax(),
                f.salaryCurrency(),
                f.salaryPeriod(),
                version(f.version())));
    return content(model, result.profile());
  }

  @PutMapping(BASE + "/skills")
  String replaceSkills(Principal principal, Model model, @ModelAttribute SetForm f) {
    return content(
        model,
        skills.execute(
            new ReplaceCandidateSkillsCommand(
                caller(principal), version(f.version()), CandidateSetsForm.skills(f.text()))));
  }

  @PutMapping(BASE + "/languages")
  String replaceLanguages(Principal principal, Model model, @ModelAttribute SetForm f) {
    return content(
        model,
        languages.execute(
            new ReplaceCandidateLanguagesCommand(
                caller(principal), version(f.version()), CandidateSetsForm.languages(f.text()))));
  }

  @PutMapping(BASE + "/work-authorizations")
  String replaceWorkAuthorizations(Principal principal, Model model, @ModelAttribute SetForm f) {
    return content(
        model,
        authorizations.execute(
            new ReplaceWorkAuthorizationsCommand(
                caller(principal), version(f.version()), CandidateSetsForm.countries(f.text()))));
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
    fill(model, currentProfile(principal));
    model.addAttribute(
        "notice",
        "Tu perfil cambió desde que lo abriste, en otra pestaña o dispositivo. Lo recargamos: revisa y repite tu cambio.");
    return CONTENT_FRAGMENT;
  }

  /** 404 y el contenido recargado: no hay perfil de candidato que modificar todavía. */
  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  String notFound(Principal principal, Model model) {
    fill(model, currentProfile(principal));
    model.addAttribute(
        "notice", "Primero guarda tus datos de contacto para crear tu perfil de candidato.");
    return CONTENT_FRAGMENT;
  }

  /** Sin la capacidad de candidato no hay pantalla: el HTML lo dice sin ofrecer los formularios. */
  @ExceptionHandler(ForbiddenCapabilityException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  String forbidden(Model model) {
    return "cv/candidate-forbidden";
  }

  // ---- auxiliares ----

  private String content(Model model, CandidateProfileView view) {
    fill(model, Optional.of(view));
    return CONTENT_FRAGMENT;
  }

  private void fill(Model model, Optional<CandidateProfileView> view) {
    model.addAttribute("profile", CandidateProfileResponse.from(view));
    var sets = view.map(CandidateProfileView::collections);
    model.addAttribute(
        "skillsText", sets.map(s -> CandidateSetsForm.skillsText(s.skills())).orElse(""));
    model.addAttribute(
        "languagesText", sets.map(s -> CandidateSetsForm.languagesText(s.languages())).orElse(""));
    model.addAttribute(
        "countriesText", sets.map(s -> String.join(", ", s.workAuthorizations())).orElse(""));
  }

  private Optional<CandidateProfileView> currentProfile(Principal principal) {
    return getProfile.execute(new GetMyCandidateProfileQuery(caller(principal)));
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
