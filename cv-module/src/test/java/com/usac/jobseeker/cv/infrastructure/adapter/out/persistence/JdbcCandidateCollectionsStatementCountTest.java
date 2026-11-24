package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.domain.model.CandidateDetails;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateProfile;
import com.usac.jobseeker.cv.domain.model.CandidateProfileId;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import com.usac.jobseeker.cv.infrastructure.CvPostgres;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Reemplazar un conjunto cuesta un número fijo de sentencias, no uno por elemento: antes eran hasta
 * 100 viajes a la base de datos en una sola petición, dentro de la transacción que bloquea la fila
 * del perfil. Se cuentan las sentencias que de verdad llegan al controlador JDBC.
 */
class JdbcCandidateCollectionsStatementCountTest {

  private final AtomicInteger statements = new AtomicInteger();
  private final JdbcClient plain = JdbcClient.create(CvPostgres.dataSource());
  private final JdbcCandidateCollectionsRepository counted =
      new JdbcCandidateCollectionsRepository(JdbcClient.create(counting(CvPostgres.dataSource())));

  private DataSource counting(DataSource real) {
    return new DelegatingDataSource(real) {
      @Override
      public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("prepareStatement")
                      || method.getName().equals("createStatement")) {
                    statements.incrementAndGet();
                  }
                  try {
                    return method.invoke(connection, args);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }

  private CandidateProfileId aProfile() {
    AccountId account = new AccountId(UUID.randomUUID());
    plain
        .sql("insert into accounts (id, is_candidate_enabled) values (:id, true)")
        .param("id", account.value())
        .update();
    var details =
        CandidateDetails.of("a@b.co", null, null, null, null, null, null, null, null, null);
    var profile =
        CandidateProfile.create(
            new CandidateProfileId(UUID.randomUUID()),
            account,
            details,
            Instant.parse("2026-11-24T10:00:00Z"));
    new JdbcCandidateProfileRepository(plain).insertIfAbsent(profile);
    return profile.id();
  }

  @Test
  void aHundredSkillsCostTwoStatementsAndAreAllStored() {
    var profile = aProfile();
    var skills =
        IntStream.range(0, 100)
            .mapToObj(i -> new CandidateSkill("Skill " + i, i % 2 == 0 ? null : 3))
            .toList();

    statements.set(0);
    counted.replaceSkills(profile, skills);

    assertThat(statements.get()).isLessThanOrEqualTo(2);
    assertThat(counted.findByProfileId(profile).skills()).hasSize(100);
  }

  @Test
  void languagesAndAuthorizationsCostTwoStatementsEachAndAnEmptySetCostsOne() {
    var profile = aProfile();
    var languages =
        List.of(
            new CandidateLanguage("en", LanguageProficiency.B2),
            new CandidateLanguage("es", LanguageProficiency.NATIVE),
            new CandidateLanguage("fr", LanguageProficiency.A2));
    var countries = List.of("GT", "MX", "US", "CA");

    statements.set(0);
    counted.replaceLanguages(profile, languages);
    int forLanguages = statements.getAndSet(0);
    counted.replaceWorkAuthorizations(profile, countries);
    int forCountries = statements.getAndSet(0);
    counted.replaceSkills(profile, List.of());
    int forEmpty = statements.get();

    assertThat(forLanguages).isLessThanOrEqualTo(2);
    assertThat(forCountries).isLessThanOrEqualTo(2);
    assertThat(forEmpty).isEqualTo(1);
    assertThat(counted.findByProfileId(profile).languages()).hasSize(3);
    assertThat(counted.findByProfileId(profile).workAuthorizations())
        .containsExactly("CA", "GT", "MX", "US");
  }

  @Test
  void aSetWithAQuoteABackslashAndUnicodeIsStoredExactlyAsWritten() {
    var profile = aProfile();
    var tricky = List.of(new CandidateSkill("C++ \"avanzado\" \\ ñandú ☕", 2));

    counted.replaceSkills(profile, tricky);

    assertThat(counted.findByProfileId(profile).skills()).containsExactlyElementsOf(tricky);
  }
}
