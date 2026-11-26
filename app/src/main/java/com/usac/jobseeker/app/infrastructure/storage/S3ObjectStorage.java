package com.usac.jobseeker.app.infrastructure.storage;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileType;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * El almacén por el protocolo S3 (ADR-0021 §1): el mismo código habla con SeaweedFS en desarrollo y
 * con Supabase Storage en producción. Cualquier fallo del servicio se traduce a {@link
 * UpstreamUnavailableException}, que la API convierte en 503; los módulos nunca ven el SDK.
 */
final class S3ObjectStorage implements ObjectStoragePort, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(S3ObjectStorage.class);
  private static final String DEPENDENCY = "storage";
  private static final int NOT_FOUND = 404;
  private static final Pattern NOT_ASCII = Pattern.compile("[^\\x20-\\x7E]");

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;
  private final Duration downloadTtl;

  S3ObjectStorage(S3Client s3, S3Presigner presigner, String bucket, Duration downloadTtl) {
    this.s3 = Objects.requireNonNull(s3, "s3");
    this.presigner = Objects.requireNonNull(presigner, "presigner");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
    this.downloadTtl = Objects.requireNonNull(downloadTtl, "downloadTtl");
  }

  @Override
  public StoredObjectKey put(FilePurpose purpose, byte[] content, FileType type) {
    StoredObjectKey key = StoredObjectKey.random(purpose);
    try {
      s3.putObject(
          b -> b.bucket(bucket).key(key.value()).contentType(type.mediaType()),
          RequestBody.fromBytes(content));
      return key;
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  @Override
  public URI presignDownload(StoredObjectKey key, String displayName, FileType type) {
    GetObjectRequest get =
        GetObjectRequest.builder()
            .bucket(bucket)
            .key(key.value())
            .responseContentDisposition(attachment(displayName))
            .responseContentType(type.mediaType())
            .build();
    try {
      return presigner
          .presignGetObject(p -> p.signatureDuration(downloadTtl).getObjectRequest(get))
          .url()
          .toURI();
    } catch (SdkException | java.net.URISyntaxException e) {
      throw unavailable(e);
    }
  }

  // S3 trata borrar lo que no existe como un éxito, que es lo que pide el reintento (§6).
  @Override
  public void delete(StoredObjectKey key) {
    try {
      s3.deleteObject(b -> b.bucket(bucket).key(key.value()));
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  @Override
  public boolean exists(StoredObjectKey key) {
    try {
      s3.headObject(b -> b.bucket(bucket).key(key.value()));
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (S3Exception e) {
      if (e.statusCode() == NOT_FOUND) {
        return false;
      }
      throw unavailable(e);
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  /** Comprueba que el *bucket* responde; lo usa el indicador de salud. */
  void ping() {
    try {
      s3.headBucket(b -> b.bucket(bucket));
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  // Solo en desarrollo (create-bucket-if-missing): en producción el bucket lo crea operaciones.
  void createBucketIfMissing() {
    try {
      s3.headBucket(b -> b.bucket(bucket));
    } catch (NoSuchBucketException e) {
      createBucket();
    } catch (S3Exception e) {
      if (e.statusCode() != NOT_FOUND) {
        throw unavailable(e);
      }
      createBucket();
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  private void createBucket() {
    try {
      s3.createBucket(b -> b.bucket(bucket));
      LOG.info("event=storage_bucket_created Se creó el bucket del almacén de desarrollo");
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  // Spring lo llama al apagar (método de destrucción inferido): libera las conexiones del cliente.
  @Override
  public void close() {
    presigner.close();
    s3.close();
  }

  /**
   * `attachment` obliga a descargar en vez de mostrar. `filename*` (RFC 5987) conserva tildes y
   * eñes; `filename` va en ASCII para clientes antiguos. El nombre ya viene saneado
   * (`FileContentRules.displayName`), así que no trae comillas ni saltos de línea.
   */
  static String attachment(String displayName) {
    String ascii = NOT_ASCII.matcher(displayName).replaceAll("_");
    String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
  }

  private static UpstreamUnavailableException unavailable(Exception cause) {
    return new UpstreamUnavailableException(DEPENDENCY, cause);
  }
}
