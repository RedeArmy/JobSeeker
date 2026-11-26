package com.usac.jobseeker.app.infrastructure.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Conexión con el almacén de archivos por el protocolo S3 (ADR-0021): SeaweedFS en desarrollo y CI,
 * Supabase Storage en producción. Sin `endpoint` el almacén se considera **no configurado** y cada
 * operación responde 503 (ver {@link UnavailableObjectStorage}); nunca se cae al punto de acceso
 * por defecto del SDK, que es el de AWS.
 *
 * @param endpoint URL del servicio S3; vacía significa no configurado
 * @param region región que firma las peticiones (Supabase usa la de su proyecto)
 * @param bucket el único *bucket*, privado, donde viven todos los archivos
 * @param accessKey credencial de acceso; solo en el entorno, nunca en el repositorio
 * @param secretKey secreto de la credencial
 * @param pathStyle direcciones `endpoint/bucket/clave` en vez de subdominio por *bucket*
 * @param downloadTtl vida de una URL de descarga firmada; como mucho 5 minutos (amenaza T-N10)
 * @param connectTimeout tiempo máximo para conectar
 * @param readTimeout tiempo máximo de una operación
 * @param createBucketIfMissing crear el *bucket* al arrancar si no existe; solo en desarrollo
 */
@ConfigurationProperties("jobseeker.storage")
public record StorageProperties(
    String endpoint,
    @DefaultValue("us-east-1") String region,
    String bucket,
    String accessKey,
    String secretKey,
    @DefaultValue("true") boolean pathStyle,
    @DefaultValue("5m") Duration downloadTtl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("10s") Duration readTimeout,
    @DefaultValue("false") boolean createBucketIfMissing) {

  private static final Duration MAX_DOWNLOAD_TTL = Duration.ofMinutes(5);

  public StorageProperties {
    if (downloadTtl.isZero()
        || downloadTtl.isNegative()
        || downloadTtl.compareTo(MAX_DOWNLOAD_TTL) > 0) {
      throw new IllegalArgumentException(
          "jobseeker.storage.download-ttl debe estar entre 0 y 5 minutos (ADR-0021, amenaza T-N10)");
    }
    // A medio configurar es un error de despliegue, no «sin almacén»: se detecta al arrancar.
    if (present(endpoint) && !(present(bucket) && present(accessKey) && present(secretKey))) {
      throw new IllegalArgumentException(
          "jobseeker.storage: con endpoint hacen falta también bucket, access-key y secret-key");
    }
  }

  /** Si hay un almacén al que conectarse. */
  public boolean configured() {
    return present(endpoint);
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
