package com.usac.jobseeker.cv.infrastructure.config;

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
 */
@ConfigurationProperties("jobseeker.cv")
record CvFileProperties(
    @DefaultValue("5MB") DataSize cvMaxSize, @DefaultValue("20") int cvMaxVersions) {}
