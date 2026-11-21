package com.usac.jobseeker.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

@Configuration
@EnableRedisIndexedHttpSession
class DistributedSessionConfig {
  // ADR-0020 regla 9: sin CONFIG al arrancar (ConfigureRedisAction.NO_OP) para tolerar un Redis
  // gestionado.
  @Bean
  static ConfigureRedisAction configureRedisAction() {
    return ConfigureRedisAction.NO_OP;
  }
}
