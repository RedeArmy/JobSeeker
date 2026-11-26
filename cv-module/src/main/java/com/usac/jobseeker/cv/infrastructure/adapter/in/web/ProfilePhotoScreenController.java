package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static com.usac.jobseeker.cv.infrastructure.adapter.in.web.WebInputs.caller;

import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoQuery;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoUseCase;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoCommand;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoUseCase;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.UploadProfilePhotoCommand;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.UploadProfilePhotoUseCase;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * La foto en la pantalla del perfil profesional (`tdd/cv-module.md` §10.8). Es una sección propia,
 * que la página carga aparte, para que cambiar la foto no recargue el formulario del perfil ni
 * pierda lo que se esté escribiendo en él.
 *
 * <p>La página del perfil manda todo `422` a su zona de errores; esta sección redirige los suyos a
 * sí misma con `HX-Retarget` y `HX-Reswap`, que HTMX aplica después de esa regla. Un antivirus
 * caído también responde `422` con su mensaje, como en la pantalla del CV.
 */
@Controller
public class ProfilePhotoScreenController {

  private static final String SECTION_FRAGMENT = "cv/profile-photo :: section";
  private static final String BASE = "/app/profile/photo";
  private static final String SECTION_ID = "#photo-section";
  private static final String NOTICE = "notice";
  private static final String ERROR = "error";
  private static final Duration CACHE = Duration.ofMinutes(5);

  private final UploadProfilePhotoUseCase uploadPhoto;
  private final GetMyProfilePhotoUseCase getPhoto;
  private final RemoveProfilePhotoUseCase removePhoto;

  public ProfilePhotoScreenController(
      UploadProfilePhotoUseCase uploadPhoto,
      GetMyProfilePhotoUseCase getPhoto,
      RemoveProfilePhotoUseCase removePhoto) {
    this.uploadPhoto = uploadPhoto;
    this.getPhoto = getPhoto;
    this.removePhoto = removePhoto;
  }

  @GetMapping(BASE + "-section")
  String section(Principal principal, Model model) {
    fill(model, caller(principal));
    return SECTION_FRAGMENT;
  }

  /** La imagen para la etiqueta `<img>`: del propio origen, como exige la CSP. */
  @GetMapping(BASE)
  ResponseEntity<byte[]> image(Principal principal) {
    var photo =
        getPhoto
            .read(new GetMyProfilePhotoQuery(caller(principal)))
            .orElseThrow(NotFoundException::new);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(photo.mediaType()))
        .cacheControl(CacheControl.maxAge(CACHE).cachePrivate())
        .body(photo.content());
  }

  @PostMapping(BASE)
  String upload(
      Principal principal,
      Model model,
      HttpServletResponse response,
      @RequestParam(name = "file", required = false) MultipartFile file) {
    AccountId account = caller(principal);
    if (file == null || file.isEmpty()) {
      return failed(model, response, account, "Elige una imagen antes de subirla.");
    }
    uploadPhoto.execute(new UploadProfilePhotoCommand(account, ProfilePhotoController.bytes(file)));
    model.addAttribute(NOTICE, "Tu foto quedó guardada.");
    fill(model, account);
    return SECTION_FRAGMENT;
  }

  @DeleteMapping(BASE)
  String remove(Principal principal, Model model) {
    AccountId account = caller(principal);
    removePhoto.execute(new RemoveProfilePhotoCommand(account));
    model.addAttribute(NOTICE, "Quitamos tu foto.");
    fill(model, account);
    return SECTION_FRAGMENT;
  }

  // ---- errores ----

  @ExceptionHandler(FileRejectedException.class)
  String rejected(
      FileRejectedException failure,
      Principal principal,
      Model model,
      HttpServletResponse response) {
    return failed(model, response, caller(principal), FileMessages.photo(failure.reason()));
  }

  @ExceptionHandler(UpstreamUnavailableException.class)
  String unavailable(Principal principal, Model model, HttpServletResponse response) {
    return failed(model, response, caller(principal), FileMessages.UNAVAILABLE);
  }

  /** Sin perfil profesional no hay dónde guardar la foto: la sección lo dice. */
  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  String notFound(Principal principal, Model model) {
    fill(model, caller(principal));
    model.addAttribute(ERROR, "Primero guarda tu perfil profesional con tu nombre.");
    return SECTION_FRAGMENT;
  }

  // ---- auxiliares ----

  private String failed(
      Model model, HttpServletResponse response, AccountId account, String error) {
    response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
    response.setHeader("HX-Retarget", SECTION_ID);
    response.setHeader("HX-Reswap", "outerHTML");
    model.addAttribute(ERROR, error);
    fill(model, account);
    return SECTION_FRAGMENT;
  }

  private void fill(Model model, AccountId account) {
    var photo = getPhoto.describe(new GetMyProfilePhotoQuery(account));
    model.addAttribute("photo", photo.orElse(null));
  }
}
