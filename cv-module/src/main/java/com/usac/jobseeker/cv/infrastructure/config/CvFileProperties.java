package com.usac.jobseeker.cv.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Límites del CV (ADR-0021 §4 y §5, decisiones R2 y R3). El tamaño máximo del `multipart` de Spring
 * debe ser algo mayor, para que un archivo de 5 a 6 MB reciba el `FILE_REJECTED` del caso de uso y
 * no un error genérico.
 *
 * @param cvMaxSize tamaño máximo de un CV
 * @param cvMaxVersions versiones del CV que una cuenta puede guardar
 * @param orphanGrace edad mínima de un objeto para juzgarlo huérfano (TD-041)
 * @param orphanPageSize objetos por página al listar el almacén
 * @param orphanScanLimit objetos que examina una pasada
 * @param orphanDeleteLimit freno: más huérfanos que esto en una pasada no borra ninguno
 */
@ConfigurationProperties("jobseeker.cv")
record CvFileProperties(
    @DefaultValue("5MB") DataSize cvMaxSize,
    @DefaultValue("20") int cvMaxVersions,
    @DefaultValue("24h") Duration orphanGrace,
    @DefaultValue("500") int orphanPageSize,
    @DefaultValue("10000") int orphanScanLimit,
    @DefaultValue("500") int orphanDeleteLimit) {}
