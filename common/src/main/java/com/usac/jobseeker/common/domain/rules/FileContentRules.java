package com.usac.jobseeker.common.domain.rules;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Qué es de verdad un archivo subido y con qué nombre se muestra (ADR-0021 §2). El tipo se decide
 * por los primeros bytes y la estructura, nunca por la extensión ni por el `Content-Type` del
 * cliente, que cualquiera puede falsear.
 *
 * <p>Un DOCX es un ZIP. Para reconocerlo y rechazar los que traen macros se lee **solo el
 * directorio central** (los nombres de las entradas), sin descomprimir nada: un ZIP diseñado para
 * expandirse sin límite (*zip bomb*) no cuesta aquí ni memoria ni CPU.
 */
public final class FileContentRules {

  private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] ZIP = {'P', 'K', 3, 4};

  // Firmas y tamaños fijos del formato ZIP (APPNOTE 4.3.12 y 4.3.16).
  private static final int END_OF_DIRECTORY = 0x06054b50;
  private static final int DIRECTORY_ENTRY = 0x02014b50;
  private static final int END_RECORD_SIZE = 22;
  private static final int MAX_COMMENT = 0xFFFF;
  private static final int ENTRY_HEADER_SIZE = 46;
  // Un DOCX normal tiene unas decenas de entradas; más que esto no es un documento de texto.
  private static final int MAX_ENTRIES = 2_000;

  private static final String CONTENT_TYPES = "[Content_Types].xml";
  private static final String MAIN_DOCUMENT = "word/document.xml";
  private static final String MACROS = "vbaproject.bin";

  private static final int MAX_NAME_LENGTH = 100;
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{N} ._-]+");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private FileContentRules() {}

  /**
   * El tipo real del contenido, si es uno de los aceptados. Un DOCX con macros (`vbaProject.bin`)
   * no se reconoce: se rechaza como tipo no admitido.
   */
  public static Optional<FileType> detect(byte[] content) {
    if (content == null || content.length == 0) {
      return Optional.empty();
    }
    if (startsWith(content, PDF)) {
      return Optional.of(FileType.PDF);
    }
    if (startsWith(content, PNG)) {
      return Optional.of(FileType.PNG);
    }
    if (startsWith(content, JPEG)) {
      return Optional.of(FileType.JPEG);
    }
    if (startsWith(content, ZIP) && isDocxWithoutMacros(content)) {
      return Optional.of(FileType.DOCX);
    }
    return Optional.empty();
  }

  /**
   * El nombre con que se muestra y se descarga: sin ruta, sin caracteres de control ni comillas, de
   * longitud acotada y con la extensión **del tipo detectado**, no la que traía. Un nombre que
   * queda vacío pasa a `archivo`. Nunca se usa como clave de almacenamiento (ADR-0021 §2).
   */
  public static String displayName(String original, FileType type) {
    String base = original == null ? "" : original;
    int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
    base = base.substring(slash + 1);
    int dot = base.lastIndexOf('.');
    if (dot > 0) {
      base = base.substring(0, dot);
    }
    base = Normalizer.normalize(base, Normalizer.Form.NFC);
    base = NOT_ALLOWED.matcher(base).replaceAll(" ");
    base = SPACES.matcher(base).replaceAll(" ").strip();
    // Sin puntos al inicio: un nombre como «.bashrc» o «..» no debe parecer oculto ni una ruta.
    while (base.startsWith(".")) {
      base = base.substring(1).strip();
    }
    if (base.isEmpty()) {
      base = "archivo";
    }
    String suffix = "." + type.extension();
    if (base.length() + suffix.length() > MAX_NAME_LENGTH) {
      base = base.substring(0, MAX_NAME_LENGTH - suffix.length()).strip();
    }
    return base + suffix;
  }

  private static boolean startsWith(byte[] content, byte[] prefix) {
    if (content.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (content[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  // Recorre el directorio central y decide por los nombres: tiene el documento principal y el
  // catálogo de tipos, y no tiene el proyecto de macros. Un ZIP mal formado no es un DOCX.
  private static boolean isDocxWithoutMacros(byte[] content) {
    Set<String> names = entryNames(content);
    if (names.isEmpty()) {
      return false;
    }
    boolean macros = names.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).endsWith(MACROS));
    return !macros && names.contains(CONTENT_TYPES) && names.contains(MAIN_DOCUMENT);
  }

  private static Set<String> entryNames(byte[] content) {
    ByteBuffer zip = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
    int end = findEndOfDirectory(zip);
    if (end < 0) {
      return Set.of();
    }
    int entries = Short.toUnsignedInt(zip.getShort(end + 10));
    long offset = Integer.toUnsignedLong(zip.getInt(end + 16));
    if (entries > MAX_ENTRIES || offset >= content.length) {
      return Set.of();
    }
    Set<String> names = new HashSet<>();
    int position = (int) offset;
    for (int i = 0; i < entries; i++) {
      if (position + ENTRY_HEADER_SIZE > content.length
          || zip.getInt(position) != DIRECTORY_ENTRY) {
        return Set.of();
      }
      int nameLength = Short.toUnsignedInt(zip.getShort(position + 28));
      int extraLength = Short.toUnsignedInt(zip.getShort(position + 30));
      int commentLength = Short.toUnsignedInt(zip.getShort(position + 32));
      int nameStart = position + ENTRY_HEADER_SIZE;
      if (nameStart + nameLength > content.length) {
        return Set.of();
      }
      names.add(new String(content, nameStart, nameLength, StandardCharsets.UTF_8));
      position = nameStart + nameLength + extraLength + commentLength;
    }
    return names;
  }

  // El registro final está en los últimos 22 bytes más, como mucho, un comentario de 64 KiB.
  private static int findEndOfDirectory(ByteBuffer zip) {
    int last = zip.capacity() - END_RECORD_SIZE;
    int first = Math.max(0, last - MAX_COMMENT);
    for (int i = last; i >= first; i--) {
      if (zip.getInt(i) == END_OF_DIRECTORY) {
        return i;
      }
    }
    return -1;
  }
}
