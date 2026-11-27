package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static com.usac.jobseeker.cv.infrastructure.adapter.in.web.WebInputs.caller;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoQuery;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoUseCase;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.ProfilePhotoView;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoCommand;
import com.usac.jobseeker.cv.application.usecase.removeprofilephoto.RemoveProfilePhotoUseCase;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.UploadProfilePhotoCommand;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.UploadProfilePhotoUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * La foto del perfil profesional propio (ADR-0021, `tdd/cv-module.md` §10.8). La imagen se sirve a
 * través de la aplicación y no por URL firmada: una imagen que se muestra en una página solo puede
 * venir del propio origen (CSP `img-src 'self'`). Se marca privada: es de una persona y no debe
 * guardarla un intermediario.
 */
@RestController
@Tag(name = "cv", description = "Perfil profesional y de candidato (módulo cv).")
public class ProfilePhotoController {

  private static final String PATH = "/api/v1/me/professional-profile/photo";
  private static final String BEARER = ApiDocs.BEARER;
  private static final Duration CACHE = Duration.ofMinutes(5);

  private final UploadProfilePhotoUseCase uploadPhoto;
  private final GetMyProfilePhotoUseCase getPhoto;
  private final RemoveProfilePhotoUseCase removePhoto;

  public ProfilePhotoController(
      UploadProfilePhotoUseCase uploadPhoto,
      GetMyProfilePhotoUseCase getPhoto,
      RemoveProfilePhotoUseCase removePhoto) {
    this.uploadPhoto = uploadPhoto;
    this.getPhoto = getPhoto;
    this.removePhoto = removePhoto;
  }

  /** La foto recién guardada: su tipo y la etiqueta que cambia con cada foto nueva. */
  @Schema(description = "Mi foto de perfil.")
  record PhotoResponse(
      @Schema(allowableValues = {"PNG", "JPEG"}) String type,
      @Schema(description = "Cambia con cada foto nueva; sirve para evitar la caché.") String tag) {

    static PhotoResponse from(ProfilePhotoView view) {
      return new PhotoResponse(view.type(), view.tag());
    }
  }

  @PostMapping(path = PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(
      summary = "Subir mi foto de perfil",
      description =
          "PNG o JPEG de hasta 2 MB. Se escanea con el antivirus, se reduce a 512 px y se vuelve a"
              + " codificar, lo que elimina los metadatos (también la ubicación GPS). Reemplaza la"
              + " anterior.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = "La foto guardada.")
  @ApiResponse(
      responseCode = "422",
      description = "La imagen no se acepta (`FILE_REJECTED`).",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "503",
      description = "El almacén o el antivirus no responden (`UPSTREAM_UNAVAILABLE`).",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "404",
      description = "Aún no hay perfil profesional.",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @ProfilePhotoResponses
  public ResponseEntity<PhotoResponse> upload(
      Principal principal, @RequestPart(name = "file", required = false) MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ValidationFailedException(List.of(new FieldViolation("file", "REQUIRED")));
    }
    var view = uploadPhoto.execute(new UploadProfilePhotoCommand(caller(principal), bytes(file)));
    return ResponseEntity.status(HttpStatus.CREATED).body(PhotoResponse.from(view));
  }

  @GetMapping(PATH)
  @Operation(
      summary = "Ver mi foto de perfil",
      description = "La imagen, con su tipo. `404` si no hay foto.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "La imagen.")
  @ApiResponse(
      responseCode = "404",
      description = "No hay foto.",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @ProfilePhotoResponses
  public ResponseEntity<byte[]> photo(Principal principal) {
    var photo =
        getPhoto
            .read(new GetMyProfilePhotoQuery(caller(principal)))
            .orElseThrow(NotFoundException::new);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(photo.mediaType()))
        .cacheControl(CacheControl.maxAge(CACHE).cachePrivate())
        .body(photo.content());
  }

  @DeleteMapping(PATH)
  @Operation(
      summary = "Quitar mi foto de perfil",
      description = "Quitar una foto que no hay no es un error.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = "204", description = "Quitada.")
  @ApiResponse(
      responseCode = "404",
      description = "Aún no hay perfil profesional.",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @ProfilePhotoResponses
  public ResponseEntity<Void> remove(Principal principal) {
    removePhoto.execute(new RemoveProfilePhotoCommand(caller(principal)));
    return ResponseEntity.noContent().build();
  }

  static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
