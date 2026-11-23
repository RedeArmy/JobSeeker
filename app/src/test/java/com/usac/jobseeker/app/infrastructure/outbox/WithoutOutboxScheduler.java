package com.usac.jobseeker.app.infrastructure.outbox;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Quita el planificador real de la bandeja del contexto de una prueba.
 *
 * <p>Ese planificador sondea con los consumidores reales, y ante un tipo de evento que nadie
 * atiende lo da por procesado. Si corre en el mismo contexto que una prueba que reclama filas por
 * su cuenta (con consumidores propios o llamando a `pollOnce()` en el momento que le conviene), se
 * las lleva antes: su primera pasada es inmediata al arrancar el contexto y después se repite cada
 * intervalo. Con un intervalo corto el fallo se reproduce casi siempre; con 1 h, solo cuando esa
 * primera pasada coincide con la prueba, que es como apareció en CI. Sin ningún otro sondeador,
 * cada prueba decide cuándo y quién procesa.
 *
 * <p>Vive en este paquete porque el planificador es una clase interna de visibilidad de paquete y
 * solo aquí se puede nombrar; las pruebas de otros paquetes usan esta anotación pública.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@MockitoBean(types = OutboxConfiguration.OutboxScheduler.class)
public @interface WithoutOutboxScheduler {}
