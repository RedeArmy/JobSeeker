package com.usac.jobseeker.common.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class FileContentRulesTest {

  private static final String CONTENT_TYPES = "[Content_Types].xml";
  private static final String DOCUMENT = "word/document.xml";

  @Test
  void eachAcceptedTypeIsRecognizedByItsContent() {
    assertThat(FileContentRules.detect(ascii("%PDF-1.7\n..."))).contains(FileType.PDF);
    assertThat(FileContentRules.detect(bytes(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0)))
        .contains(FileType.PNG);
    assertThat(FileContentRules.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).contains(FileType.JPEG);
    assertThat(FileContentRules.detect(zip(CONTENT_TYPES, DOCUMENT))).contains(FileType.DOCX);
  }

  // ADR-0021 §2: el tipo sale del contenido; una extensión o un Content-Type falsos no cuentan.
  @Test
  void anythingElseIsNotAnAcceptedType() {
    assertThat(FileContentRules.detect(null)).isEmpty();
    assertThat(FileContentRules.detect(new byte[0])).isEmpty();
    assertThat(FileContentRules.detect(ascii("<html><script>"))).isEmpty();
    assertThat(FileContentRules.detect(ascii("MZ ejecutable de Windows"))).isEmpty();
    assertThat(FileContentRules.detect(ascii("%PD"))).isEmpty();
  }

  @Test
  void aDocxWithMacrosIsRejected() {
    assertThat(FileContentRules.detect(zip(CONTENT_TYPES, DOCUMENT, "word/vbaProject.bin")))
        .isEmpty();
    assertThat(FileContentRules.detect(zip(CONTENT_TYPES, DOCUMENT, "word/VBAPROJECT.BIN")))
        .isEmpty();
  }

  @Test
  void aZipThatIsNotAWordDocumentIsRejected() {
    assertThat(FileContentRules.detect(zip("foto.jpg", "notas.txt"))).isEmpty();
    assertThat(FileContentRules.detect(zip(CONTENT_TYPES, "xl/workbook.xml"))).isEmpty();
    assertThat(FileContentRules.detect(zip(DOCUMENT))).isEmpty();
  }

  @Test
  void aTruncatedOrCorruptZipIsRejectedWithoutFailing() {
    byte[] docx = zip(CONTENT_TYPES, DOCUMENT);

    assertThat(FileContentRules.detect(Arrays.copyOf(docx, docx.length / 2))).isEmpty();
    assertThat(FileContentRules.detect(Arrays.copyOf(docx, 10))).isEmpty();
    byte[] corrupt = docx.clone();
    int end = lastIndexOf(corrupt, bytes('P', 'K', 5, 6));
    // Desplazamiento del directorio central (bytes 16 a 19 del registro final) fuera del archivo.
    corrupt[end + 19] = (byte) 0x7F;
    assertThat(FileContentRules.detect(corrupt)).isEmpty();
  }

  // Solo se lee el directorio central: 50 MB de ceros comprimidos no se descomprimen.
  @Test
  void aZipBombIsInspectedWithoutDecompressingIt() {
    byte[] bomb = zipWithLargeEntry(CONTENT_TYPES, DOCUMENT, 50 * 1024 * 1024);
    long start = System.nanoTime();

    var type = FileContentRules.detect(bomb);

    assertThat(type).contains(FileType.DOCX);
    assertThat(bomb.length).isLessThan(1024 * 1024);
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
  }

  @Test
  void theDisplayNameKeepsReadableTextAndTheDetectedExtension() {
    assertThat(FileContentRules.displayName("CV José Pérez.pdf", FileType.PDF))
        .isEqualTo("CV José Pérez.pdf");
    assertThat(FileContentRules.displayName("curriculum.exe", FileType.PDF))
        .isEqualTo("curriculum.pdf");
    assertThat(FileContentRules.displayName("foto", FileType.JPEG)).isEqualTo("foto.jpg");
  }

  // Una ruta, comillas o saltos de línea en el nombre romperían la cabecera Content-Disposition o
  // simularían otra ubicación; se quitan, nunca se interpretan.
  @Test
  void theDisplayNameHasNoPathNorCharactersThatCouldBreakAHeader() {
    assertThat(FileContentRules.displayName("../../etc/passwd", FileType.PDF))
        .isEqualTo("passwd.pdf");
    assertThat(FileContentRules.displayName("C:\\Users\\ana\\cv.docx", FileType.DOCX))
        .isEqualTo("cv.docx");
    assertThat(FileContentRules.displayName("cv\"; filename=\"x.html\r\n.pdf", FileType.PDF))
        .doesNotContain("\"", "\r", "\n", ";", "/");
    assertThat(FileContentRules.displayName("..pdf", FileType.PDF)).isEqualTo("archivo.pdf");
    assertThat(FileContentRules.displayName(".oculto.pdf", FileType.PDF)).isEqualTo("oculto.pdf");
  }

  @Test
  void anEmptyOrUnusableNameFallsBackToAGenericOne() {
    assertThat(FileContentRules.displayName(null, FileType.PDF)).isEqualTo("archivo.pdf");
    assertThat(FileContentRules.displayName("", FileType.PNG)).isEqualTo("archivo.png");
    assertThat(FileContentRules.displayName("<<<>>>.pdf", FileType.PDF)).isEqualTo("archivo.pdf");
  }

  @Test
  void aVeryLongNameIsCutKeepingItsExtension() {
    String name = FileContentRules.displayName("a".repeat(500) + ".pdf", FileType.PDF);

    assertThat(name).hasSize(100).endsWith(".pdf");
  }

  private static int lastIndexOf(byte[] data, byte[] pattern) {
    for (int i = data.length - pattern.length; i >= 0; i--) {
      if (Arrays.equals(data, i, i + pattern.length, pattern, 0, pattern.length)) {
        return i;
      }
    }
    throw new IllegalStateException("patrón no encontrado");
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  private static byte[] zip(String... names) {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.setComment("comentario del archivo");
      for (String name : names) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(("contenido de " + name).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return out.toByteArray();
  }

  private static byte[] zipWithLargeEntry(String contentTypes, String document, int size) {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(contentTypes));
      zip.write(new byte[16]);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(document));
      byte[] zeros = new byte[1024 * 1024];
      for (int i = 0; i < size / zeros.length; i++) {
        zip.write(zeros);
      }
      zip.closeEntry();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return out.toByteArray();
  }
}
