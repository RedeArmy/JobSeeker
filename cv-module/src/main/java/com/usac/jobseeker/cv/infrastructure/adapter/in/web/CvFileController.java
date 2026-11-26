package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static com.usac.jobseeker.cv.infrastructure.adapter.in.web.WebInputs.caller;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.application.usecase.downloadmycv.DownloadMyCvQuery;
import com.usac.jobseeker.cv.application.usecase.downloadmycv.DownloadMyCvUseCase;
import com.usac.jobseeker.cv.application.usecase.getmycv.GetMyCvQuery;
import com.usac.jobseeker.cv.application.usecase.getmycv.GetMyCvUseCase;
import com.usac.jobseeker.cv.application.usecase.uploadcv.UploadCvCommand;
import com.usac.jobseeker.cv.application.usecase.uploadcv.UploadCvUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * El CV del propio candidato (ADR-0021, `tdd/cv-module.md` §10.7). Delgado y sin lógica: la
 * identidad sale del token y todo lo demás lo decide el caso de uso. La subida es `multipart`, lo
 * que la pone bajo la política de límite `upload` sin configurar la ruta.
 *
 * <p>La descarga responde `302` a una URL firmada de 5 minutos y con `no-store`: la URL es un
 * secreto al portador mientras vive y ni el navegador ni un intermediario deben guardarla.
 */
@RestController
@Tag(name = "cv", description = "Perfil profesional y de candidato (módulo cv).")
public class CvFileController {

  private static final String PATH = "/api/v1/me/candidate-profile/cv";
  private static final String BEARER = ApiDocs.BEARER;

  private final UploadCvUseCase uploadCv;
  private final GetMyCvUseCase getMyCv;
  private final DownloadMyCvUseCase downloadMyCv;

  public CvFileController(
      UploadCvUseCase upload, GetMyCvUseCase getMyCv, DownloadMyCvUseCase download) {
    this.uploadCv = upload;
    this.getMyCv = getMyCv;
    this.downloadMyCv = download;
  }

  @PostMapping(path = PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(
      summary = "Subir mi CV",
      description =
          "PDF o DOCX sin macros, de hasta 5 MB. Se valida por su contenido, se escanea con el"
              + " antivirus y queda como la versión vigente; las anteriores se conservan (hasta 20)."
              + " Subir el mismo archivo que el vigente no crea otra versión.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.CREATED, description = "La versión vigente.")
  @ApiResponse(
      responseCode = "422",
      description =
          "El archivo no se acepta (`FILE_REJECTED`): `TOO_LARGE`, `UNSUPPORTED_TYPE`,"
              + " `MALWARE_DETECTED` o `LIMIT_REACHED`.",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @ApiResponse(
      responseCode = "503",
      description = "El almacén o el antivirus no responden (`UPSTREAM_UNAVAILABLE`).",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @CandidateWriteResponses
  public ResponseEntity<CvFileResponse> upload(
      Principal principal, @RequestPart(name = "file", required = false) MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ValidationFailedException(List.of(new FieldViolation("file", "REQUIRED")));
    }
    var view =
        uploadCv.execute(
            new UploadCvCommand(caller(principal), file.getOriginalFilename(), bytes(file)));
    return ResponseEntity.status(HttpStatus.CREATED).body(CvFileResponse.from(view));
  }

  @GetMapping(PATH)
  @Operation(
      summary = "Consultar mi CV",
      description = "La versión vigente y las anteriores, sin URL de descarga.",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = ApiDocs.OK, description = "Mi CV.")
  @CandidateWriteResponses
  public CvFileResponse.MyCv mine(Principal principal) {
    return CvFileResponse.MyCv.from(getMyCv.execute(new GetMyCvQuery(caller(principal))));
  }

  @GetMapping(PATH + "/{fileId}/download")
  @Operation(
      summary = "Descargar una versión de mi CV",
      description =
          "Redirige (`302`) a una URL firmada que caduca a los 5 minutos y obliga a descargar el"
              + " archivo. Una versión de otra cuenta no existe (`404`).",
      security = @SecurityRequirement(name = BEARER))
  @ApiResponse(responseCode = "302", description = "Redirección a la URL firmada.")
  @ApiResponse(
      responseCode = "503",
      description = "El almacén no responde (`UPSTREAM_UNAVAILABLE`).",
      content = @Content(schema = @Schema(ref = ApiDocs.ERROR_ENVELOPE)))
  @CandidateWriteResponses
  public ResponseEntity<Void> download(Principal principal, @PathVariable("fileId") String fileId) {
    var url = downloadMyCv.execute(new DownloadMyCvQuery(caller(principal), fileIdOf(fileId)));
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(url)
        .cacheControl(CacheControl.noStore())
        .build();
  }

  // Un identificador mal formado es una versión que no existe, no un error de formato: no se
  // distingue de uno ajeno.
  private static UUID fileIdOf(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      throw new NotFoundException();
    }
  }

  private static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
