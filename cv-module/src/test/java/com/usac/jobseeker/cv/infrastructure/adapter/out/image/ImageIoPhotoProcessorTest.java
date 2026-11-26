package com.usac.jobseeker.cv.infrastructure.adapter.out.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.rules.FileType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** La re-codificación real con ImageIO (ADR-0021 §2): tamaño, metadatos y rechazos. */
class ImageIoPhotoProcessorTest {

  private final ImageIoPhotoProcessor processor = new ImageIoPhotoProcessor(512, 40_000_000);

  @Test
  void aLargePhotoIsReducedToTheMaximumSideKeepingItsProportion() throws IOException {
    byte[] png = encode(new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_ARGB), "png");

    var processed = processor.process(png, FileType.PNG);
    BufferedImage result = decode(processed.content());

    assertThat(processed.type()).isEqualTo(FileType.PNG);
    assertThat(result.getWidth()).isEqualTo(512);
    assertThat(result.getHeight()).isEqualTo(256);
  }

  @Test
  void aSmallPhotoKeepsItsSize() throws IOException {
    byte[] jpeg = encode(new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB), "jpeg");

    BufferedImage result = decode(processor.process(jpeg, FileType.JPEG).content());

    assertThat(result.getWidth()).isEqualTo(200);
    assertThat(result.getHeight()).isEqualTo(300);
  }

  // El objetivo de re-codificar: un EXIF (con ubicación GPS, en una foto de móvil) no sobrevive.
  @Test
  void theExifMetadataDoesNotSurvive() throws IOException {
    byte[] jpeg = encode(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpeg");
    byte[] withExif = withExifSegment(jpeg, "GPS Guatemala 14.6349,-90.5069");
    assertThat(new String(withExif, StandardCharsets.ISO_8859_1)).contains("Exif", "GPS");

    byte[] processed = processor.process(withExif, FileType.JPEG).content();

    assertThat(new String(processed, StandardCharsets.ISO_8859_1)).doesNotContain("Exif", "GPS");
    assertThat(decode(processed).getWidth()).isEqualTo(64);
  }

  @Test
  void aPngKeepsItsTransparency() throws IOException {
    BufferedImage transparent = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    byte[] png = encode(transparent, "png");

    BufferedImage result = decode(processor.process(png, FileType.PNG).content());

    assertThat(result.getColorModel().hasAlpha()).isTrue();
    assertThat(result.getRGB(0, 0) >>> 24).isZero();
  }

  @Test
  void somethingThatIsNotAnImageIsRejected() {
    byte[] garbage = "%PDF-1.7 no soy una imagen".getBytes(StandardCharsets.US_ASCII);
    byte[] truncated = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

    assertThatThrownBy(() -> processor.process(garbage, FileType.PNG))
        .isInstanceOf(FileRejectedException.class);
    assertThatThrownBy(() -> processor.process(truncated, FileType.PNG))
        .isInstanceOf(FileRejectedException.class);
  }

  // Se miran las dimensiones antes de decodificar: muchos píxeles se rechazan sin reservar memoria.
  @Test
  void anImageWithTooManyPixelsIsRejectedBeforeDecoding() throws IOException {
    var strict = new ImageIoPhotoProcessor(512, 100);
    byte[] png = encode(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png");

    assertThatThrownBy(() -> strict.process(png, FileType.PNG))
        .isInstanceOfSatisfying(
            FileRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(FileRejectedException.Reason.UNSUPPORTED_TYPE));
  }

  @Test
  void theLimitsMustBePositive() {
    assertThatThrownBy(() -> new ImageIoPhotoProcessor(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ImageIoPhotoProcessor(1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] encode(BufferedImage image, String format) throws IOException {
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  private static BufferedImage decode(byte[] content) throws IOException {
    return ImageIO.read(new ByteArrayInputStream(content));
  }

  // Inserta un segmento APP1 «Exif» justo después del marcador SOI del JPEG.
  private static byte[] withExifSegment(byte[] jpeg, String payload) {
    byte[] data = ("Exif\0\0" + payload).getBytes(StandardCharsets.ISO_8859_1);
    int length = data.length + 2;
    var out = new ByteArrayOutputStream();
    out.write(jpeg, 0, 2);
    out.write(0xFF);
    out.write(0xE1);
    out.write(length >> 8);
    out.write(length & 0xFF);
    out.write(data, 0, data.length);
    out.write(jpeg, 2, jpeg.length - 2);
    return out.toByteArray();
  }
}
