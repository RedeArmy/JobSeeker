package com.usac.jobseeker.common.domain.rules;

/**
 * Los tipos de archivo que el sistema acepta (ADR-0021 §4), con el tipo MIME con que se guardan y
 * se sirven. El tipo sale siempre del contenido ({@link FileContentRules#detect}), nunca de lo que
 * declare el cliente.
 */
public enum FileType {
  PDF("application/pdf", "pdf"),
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
  PNG("image/png", "png"),
  JPEG("image/jpeg", "jpg");

  private final String mime;
  private final String fileExtension;

  FileType(String mediaType, String extension) {
    this.mime = mediaType;
    this.fileExtension = extension;
  }

  public String mediaType() {
    return mime;
  }

  public String extension() {
    return fileExtension;
  }
}
