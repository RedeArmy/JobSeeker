package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static com.usac.jobseeker.cv.infrastructure.adapter.in.web.WebInputs.caller;

import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.downloadmycv.DownloadMyCvQuery;
import com.usac.jobseeker.cv.application.usecase.downloadmycv.DownloadMyCvUseCase;
import com.usac.jobseeker.cv.application.usecase.getmycv.CvFileView;
import com.usac.jobseeker.cv.application.usecase.getmycv.GetMyCvQuery;
import com.usac.jobseeker.cv.application.usecase.getmycv.GetMyCvUseCase;
import com.usac.jobseeker.cv.application.usecase.uploadcv.UploadCvCommand;
import com.usac.jobseeker.cv.application.usecase.uploadcv.UploadCvUseCase;
import java.security.Principal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * La pantalla del CV (Thymeleaf + HTMX, ADR-0005), con los mismos casos de uso que
 * `CvFileController` (`tdd/cv-module.md` §10.7). La subida es un formulario `multipart` por HTMX:
 * el token CSRF viaja en la cabecera que pone la página, así que lo comprueba la cadena web sin
 * leer el cuerpo (ADR-0020).
 *
 * <p>Un archivo rechazado o un antivirus caído responden `422` con el mensaje: es el código con que
 * HTMX reemplaza la zona de errores en estas pantallas, y el usuario tiene que leer qué pasó. La
 * API JSON sí distingue el `503`.
 */
@Controller
public class CvScreenController {

  private static final String PAGE_VIEW = "cv/candidate-cv";
  private static final String CONTENT_FRAGMENT = "cv/candidate-cv :: content";
  private static final String ERRORS_FRAGMENT = "cv/candidate-cv :: errors";
  private static final String BASE = "/app/candidate-cv";
  private static final String NOTICE = "notice";
  private static final String ERRORS = "errors";

  private final UploadCvUseCase uploadCv;
  private final GetMyCvUseCase getMyCv;
  private final DownloadMyCvUseCase downloadMyCv;

  public CvScreenController(
      UploadCvUseCase uploadCv, GetMyCvUseCase getMyCv, DownloadMyCvUseCase downloadMyCv) {
    this.uploadCv = uploadCv;
    this.getMyCv = getMyCv;
    this.downloadMyCv = downloadMyCv;
  }

  @GetMapping(BASE)
  String page(Principal principal, Model model) {
    fill(model, caller(principal));
    return PAGE_VIEW;
  }

  @PostMapping(BASE)
  String upload(
      Principal principal,
      Model model,
      @RequestParam(name = "file", required = false) MultipartFile file) {
    AccountId account = caller(principal);
    if (file == null || file.isEmpty()) {
      model.addAttribute(ERRORS, List.of("Elige un archivo antes de subirlo."));
      return ERRORS_FRAGMENT;
    }
    uploadCv.execute(
        new UploadCvCommand(
            account, file.getOriginalFilename(), ProfilePhotoController.bytes(file)));
    model.addAttribute(NOTICE, "Tu CV quedó guardado como la versión vigente.");
    fill(model, account);
    return CONTENT_FRAGMENT;
  }

  // Una navegación, no una petición HTMX: redirige a la URL firmada, que la CSP no restringe porque
  // no es una imagen ni un script (ADR-0021 §3).
  @GetMapping(BASE + "/{fileId}/download")
  ResponseEntity<Void> download(Principal principal, @PathVariable("fileId") String fileId) {
    UUID id;
    try {
      id = UUID.fromString(fileId);
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
    var url = downloadMyCv.execute(new DownloadMyCvQuery(caller(principal), id));
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(url)
        .cacheControl(CacheControl.noStore())
        .build();
  }

  // ---- errores: HTML en vez del sobre JSON de la API ----

  @ExceptionHandler(FileRejectedException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
  String rejected(FileRejectedException failure, Model model) {
    model.addAttribute(ERRORS, List.of(FileMessages.cv(failure.reason())));
    return ERRORS_FRAGMENT;
  }

  @ExceptionHandler(UpstreamUnavailableException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
  String unavailable(Model model) {
    model.addAttribute(ERRORS, List.of(FileMessages.UNAVAILABLE));
    return ERRORS_FRAGMENT;
  }

  /** 404: la versión no existe o no es tuya, o aún no hay perfil de candidato. */
  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  String notFound(Principal principal, Model model) {
    fill(model, caller(principal));
    model.addAttribute(NOTICE, "No encontramos ese archivo.");
    return PAGE_VIEW;
  }

  @ExceptionHandler(ForbiddenCapabilityException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  String forbidden() {
    return "cv/candidate-forbidden";
  }

  // ---- auxiliares ----

  // Sin perfil de candidato no hay CV que mostrar: la página lo explica en lugar de fallar.
  private void fill(Model model, AccountId account) {
    try {
      var mine = getMyCv.execute(new GetMyCvQuery(account));
      model.addAttribute("hasProfile", true);
      model.addAttribute("current", mine.current().map(CvRow::of).orElse(null));
      model.addAttribute("versions", mine.versions().stream().map(CvRow::of).toList());
      model.addAttribute("maxVersions", mine.maxVersions());
    } catch (NotFoundException _) {
      model.addAttribute("hasProfile", false);
    }
  }

  /** Una versión ya lista para mostrar: tamaño legible y fecha en UTC, sin depender de la vista. */
  record CvRow(UUID id, String name, String type, String size, String date, boolean current) {

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    private static final double KILOBYTE = 1024.0;

    static CvRow of(CvFileView view) {
      return new CvRow(
          view.id(),
          view.displayName(),
          view.type(),
          String.format(Locale.ROOT, "%.1f KB", view.sizeBytes() / KILOBYTE),
          DATE.format(view.uploadedAt()),
          view.current());
    }
  }
}
