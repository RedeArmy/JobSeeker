package com.usac.jobseeker.cv.application.usecase.downloadmycv;

import java.net.URI;

/**
 * Una URL firmada de corta vida para descargar una versión propia del CV (ADR-0021 §3). Una versión
 * de otra cuenta no existe para quien la pide (404, no 403). La URL es un secreto al portador
 * mientras vive: quien llama la usa y no la guarda ni la registra.
 */
public interface DownloadMyCvUseCase {
  URI execute(DownloadMyCvQuery query);
}
