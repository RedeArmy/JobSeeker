# Mapeo JPA de los dos slots de embedding

Probado el 18-nov-2026 con Spring Boot 4.1.2, Hibernate 7.4.13 y `hibernate-vector`, contra el esquema de `01-esquema.sql` y `02-cambio-de-modelo.sql`.

## Versión «expandir» (mapea los dos slots)

```java
import org.springframework.boot.persistence.autoconfigure.EntityScan; // Boot 4: paquete nuevo

@Entity @Table(name = "candidate_profiles")
public class CandidateProfile {
    @Id UUID id;
    boolean discoverable;

    // Slot vigente: la longitud es la del modelo vigente (1024 con qwen3-embedding:0.6b).
    @JdbcTypeCode(SqlTypes.VECTOR) @Array(length = 1024) @Column(name = "embedding") float[] embedding;
    @Column(name = "embedding_model") String embeddingModel;
    @Column(name = "embedding_dim") Integer embeddingDim;
    // char(64) exige JdbcTypeCode CHAR: con columnDefinition la validación de esquema rompe el arranque.
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "embedding_content_hash", length = 64) String embeddingContentHash;
    @Column(name = "embedding_truncated") boolean embeddingTruncated;
    @Column(name = "embedding_computed_at") Instant embeddingComputedAt;

    // Slot siguiente: solo existe durante un cambio de modelo.
    @JdbcTypeCode(SqlTypes.VECTOR) @Array(length = 768) @Column(name = "embedding_next") float[] embeddingNext;
    // ... mismas cinco columnas auxiliares con prefijo embedding_next_
}
```

## Versión «contraer» (mapea un slot con la longitud nueva)

Igual, sin las columnas `embedding_next*` y con `@Array(length = 768)`.

## Consulta de similitud

```java
// SET LOCAL: vale solo dentro de la transacción (un SET de sesión contamina a otros clientes del pooler).
em.createNativeQuery("set local hnsw.iterative_scan = relaxed_order").executeUpdate();
em.createQuery("select c.id from CandidateProfile c where c.discoverable = true and c.embedding is not null "
             + "order by cosine_distance(c.embedding, :q)", UUID.class)
  .setParameter("q", vector).setMaxResults(20).getResultList();
// Hibernate genera: order by (c.embedding<=>cast(? as vector)) fetch first ? rows only  → usa el índice HNSW.
```

## Comprobación obligatoria al arrancar

`spring.jpa.hibernate.ddl-auto=validate` **no** comprueba la longitud del vector. La aplicación debe consultar

```sql
select format_type(atttypid, atttypmod) from pg_attribute
 where attrelid = 'candidate_profiles'::regclass and attname = 'embedding'   -- p. ej. 'vector(768)'
```

y fallar al arrancar si no coincide con la dimensión configurada: «La columna embedding es vector(768) pero la configuración espera vector(1024): migración y versión de la aplicación desalineadas».

## Conexión

Aplicación: `jdbc:postgresql://…:6543/…?prepareThreshold=0` (pooler en modo transacción de Supabase). Flyway: conexión directa o modo sesión (5432).
