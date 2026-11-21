package com.usac.jobseeker.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Postgres y Redis reales para las pruebas que levantan el contexto completo. */
@TestConfiguration(proxyBeanMethods = false)
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
public class InfrastructureTestConfiguration {}
