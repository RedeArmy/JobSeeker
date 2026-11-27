package com.usac.jobseeker.cv.infrastructure.adapter.out.image;

import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.FileRejectedException.Reason;
import com.usac.jobseeker.common.domain.rules.FileType;
import com.usac.jobseeker.cv.application.usecase.uploadprofilephoto.PhotoProcessorPort;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Re-codifica la foto con ImageIO, del JDK (ADR-0021 §2). Al escribir una imagen nueva a partir de
 * los píxeles no se copia ningún metadato: desaparecen el EXIF y la ubicación GPS de una foto de
 * móvil. Antes de decodificar se leen solo las dimensiones, para que una imagen diminuta que dice
 * medir 50 000 × 50 000 (una «bomba de descompresión») no agote la memoria.
 */
public final class ImageIoPhotoProcessor implements PhotoProcessorPort {

  private final int maxSide;
  private final long maxPixels;

  /**
   * @param maxSide lado máximo de la foto guardada; una mayor se reduce conservando la proporción
   * @param maxPixels píxeles máximos que se aceptan decodificar
   */
  public ImageIoPhotoProcessor(int maxSide, long maxPixels) {
    if (maxSide <= 0 || maxPixels <= 0) {
      throw new IllegalArgumentException("Límites de la foto no válidos");
    }
    this.maxSide = maxSide;
    this.maxPixels = maxPixels;
  }

  // Los lectores de ImageIO lanzan excepciones no comprobadas variadas ante una imagen mal formada
  // (IllegalArgumentException, IndexOutOfBounds…): todas significan «no es una imagen válida».
  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public ProcessedPhoto process(byte[] content, FileType type) {
    try {
      BufferedImage decoded = decode(content);
      BufferedImage resized = resize(decoded, type);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(resized, type == FileType.PNG ? "png" : "jpeg", out)) {
        throw unreadable();
      }
      return new ProcessedPhoto(out.toByteArray(), type);
    } catch (FileRejectedException e) {
      throw e;
    } catch (IOException | RuntimeException _) {
      throw unreadable();
    }
  }

  private BufferedImage decode(byte[] content) throws IOException {
    try (ImageInputStream input =
        ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (input == null || !readers.hasNext()) {
        throw unreadable();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        long pixels = (long) width * height;
        if (pixels <= 0 || pixels > maxPixels) {
          throw unreadable();
        }
        // Se decodifica ya submuestreada: una foto de 40 MP a resolución completa ocupa ~160 MB
        // de montículo, y la guardada no pasa de `maxSide`. Se deja el doble de resolución para
        // que la reducción final, bicúbica, conserve la calidad.
        ImageReadParam param = reader.getDefaultReadParam();
        int step = subsampling(width, height);
        param.setSourceSubsampling(step, step, 0, 0);
        return reader.read(0, param);
      } finally {
        reader.dispose();
      }
    }
  }

  /** Cuántos píxeles de origen se saltan por cada uno leído: 1 si ya es pequeña. */
  int subsampling(int width, int height) {
    return Math.max(1, Math.max(width, height) / (maxSide * 2));
  }

  // JPEG no admite transparencia: se dibuja sobre blanco. PNG la conserva.
  private BufferedImage resize(BufferedImage source, FileType type) {
    double scale =
        Math.min(1.0, (double) maxSide / Math.max(source.getWidth(), source.getHeight()));
    int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
    boolean png = type == FileType.PNG;
    BufferedImage target =
        new BufferedImage(
            width, height, png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      if (!png) {
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
      }
      graphics.drawImage(source, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  private static FileRejectedException unreadable() {
    return new FileRejectedException(Reason.UNSUPPORTED_TYPE);
  }
}
