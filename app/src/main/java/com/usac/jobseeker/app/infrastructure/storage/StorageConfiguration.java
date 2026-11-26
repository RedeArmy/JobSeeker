package com.usac.jobseeker.app.infrastructure.storage;

import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Proveedores de los puertos de almacenamiento y escaneo (ADR-0021). Sin configuración se registran
 * los adaptadores «no disponible» y se avisa al arrancar: la aplicación funciona, pero las subidas
 * responden 503 en vez de guardar a medias.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StorageProperties.class, MalwareScanProperties.class})
class StorageConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(StorageConfiguration.class);

  // El cliente y el firmador los cierra S3ObjectStorage.close(), que Spring llama al apagar.
  @Bean
  @SuppressWarnings("PMD.CloseResource")
  ObjectStoragePort objectStorage(StorageProperties properties) {
    if (!properties.configured()) {
      LOG.warn(
          "event=storage_not_configured Almacén de archivos sin configurar: las subidas responderán"
              + " 503");
      return new UnavailableObjectStorage();
    }
    S3Configuration s3Config =
        S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle()).build();
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    URI endpoint = URI.create(properties.endpoint());
    Region region = Region.of(properties.region());
    S3Client client =
        S3Client.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
            .httpClient(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(properties.connectTimeout())
                    .socketTimeout(properties.readTimeout())
                    .build())
            .build();
    S3Presigner presigner =
        S3Presigner.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
            .build();
    S3ObjectStorage storage =
        new S3ObjectStorage(client, presigner, properties.bucket(), properties.downloadTtl());
    if (properties.createBucketIfMissing()) {
      // Solo en desarrollo: si el almacén aún no arrancó, la aplicación sigue y las subidas dan
      // 503.
      try {
        storage.createBucketIfMissing();
      } catch (UpstreamUnavailableException _) {
        LOG.warn(
            "event=storage_bucket_check_failed No se pudo comprobar el bucket del almacén al"
                + " arrancar");
      }
    }
    return storage;
  }

  @Bean
  MalwareScanPort malwareScanner(MalwareScanProperties properties, MeterRegistry meters) {
    if (!properties.configured()) {
      LOG.warn(
          "event=malware_scan_not_configured Antivirus sin configurar: las subidas responderán 503");
      return new UnavailableMalwareScanner();
    }
    return new ClamAvScanner(properties, meters);
  }

  // ADR-0021 §6: el borrado físico de lo anonimizado llega por la bandeja, con reintentos.
  @Bean
  OutboxEventConsumer fileDeletionConsumer(ObjectStoragePort storage) {
    return new FileDeletionConsumer(storage);
  }

  /**
   * Salud del almacén. Sin configurar responde `UNKNOWN`, que no baja el estado general: la prueba
   * de humo y una instalación sin almacén siguen en `UP`, y el *healthcheck* del contenedor mira
   * `readiness`, donde este indicador no participa.
   */
  @Bean
  HealthIndicator storageHealthIndicator(ObjectStoragePort storage) {
    return () -> {
      if (!(storage instanceof S3ObjectStorage s3)) {
        return Health.unknown().withDetail("configured", false).build();
      }
      try {
        s3.ping();
        return Health.up().build();
      } catch (UpstreamUnavailableException _) {
        return Health.down().build();
      }
    };
  }

  @Bean
  HealthIndicator malwareScanHealthIndicator(MalwareScanPort scanner) {
    return () -> {
      if (!(scanner instanceof ClamAvScanner clamAv)) {
        return Health.unknown().withDetail("configured", false).build();
      }
      try {
        clamAv.ping();
        return Health.up().build();
      } catch (UpstreamUnavailableException _) {
        return Health.down().build();
      }
    };
  }
}
