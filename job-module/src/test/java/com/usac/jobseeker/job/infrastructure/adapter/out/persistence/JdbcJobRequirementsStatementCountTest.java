package com.usac.jobseeker.job.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.domain.model.JobDetails;
import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.RequirementType;
import com.usac.jobseeker.job.domain.model.WorkModality;
import com.usac.jobseeker.job.infrastructure.JobPostgres;
import java.lang.reflect.InvocationTargetException;
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
 * TD-047: reemplazar los requisitos de una vacante cuesta lo mismo con uno que con treinta: un
 * borrado y una inserción. Se cuenta con sentencias preparadas, no con el reloj.
 */
class JdbcJobRequirementsStatementCountTest {

  private final AtomicInteger statements = new AtomicInteger();
  private final JdbcClient plain = JdbcClient.create(JobPostgres.dataSource());
  private final JdbcJobRequirementsRepository counted =
      new JdbcJobRequirementsRepository(JdbcClient.create(counting(JobPostgres.dataSource())));

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
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }

  private JobPostingId aPosting() {
    AccountId owner = new AccountId(UUID.randomUUID());
    plain
        .sql("insert into accounts (id, is_recruiter_enabled) values (:id, true)")
        .param("id", owner.value())
        .update();
    JobPosting posting =
        JobPosting.create(
            new JobPostingId(UUID.randomUUID()),
            owner,
            JobDetails.of(
                "Backend", "Descripción", WorkModality.REMOTE, "GT", null, null, null, null),
            Instant.parse("2026-11-27T10:00:00Z"));
    new JdbcJobPostingRepository(plain).insert(posting);
    return posting.id();
  }

  private static List<JobRequirement> skills(int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                new JobRequirement(
                    RequirementType.SKILL,
                    i % 2 == 0,
                    "Habilidad " + i,
                    null,
                    null,
                    null,
                    null,
                    null))
        .toList();
  }

  @Test
  void replacingOneOrThirtyRequirementsCostsTheSameTwoStatements() {
    JobPostingId one = aPosting();
    JobPostingId thirty = aPosting();

    statements.set(0);
    counted.replace(one, skills(1));
    int forOne = statements.get();
    statements.set(0);
    counted.replace(thirty, skills(30));
    int forThirty = statements.get();

    assertThat(forOne).isEqualTo(2);
    assertThat(forThirty).isEqualTo(forOne);
    assertThat(new JdbcJobRequirementsRepository(plain).findByPosting(thirty))
        .containsExactlyElementsOf(skills(30));
  }
}
