# 13 — Spike del ADR-0021: cliente S3 con Java 25 (V2) y ClamAV en la VM de desarrollo (V3)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Fecha** | 26 de noviembre de 2026 |
| **Documentos relacionados** | ADR-0021 (verificaciones V2 y V3), ADR-0014 (versiones de dependencias), ADR-0026 (topología), `07-spike-bibliotecas-java-25.md`, `08-medicion-ollama-y-bakeoff-embeddings.md` |

---

## 1. Resumen

| Verificación | Resultado |
|---|---|
| **V2:** AWS SDK 2.x con el cliente `url-connection` en Java 25 | ✅ **Cumple.** Subida, metadatos, URL firmada con `Content-Disposition` y tipo, firma alterada y caducada rechazadas (`403`), borrado. Sin avisos de la JVM |
| **V3:** memoria y arranque de ClamAV en la VM de desarrollo | ✅ **Cabe, con un pico mayor del estimado.** Responde a los **27 s**; **0,96 GiB** en reposo y **1,85 GiB** durante la recarga diaria de firmas (el ADR estimaba 1,2 a 1,5 GB) |
| **Hallazgo:** MinIO, el servidor de desarrollo y CI que fija el ADR-0021 | ❌ **Sus imágenes ya no son públicas:** Docker Hub responde que `minio/minio` no existe y quay.io exige autenticación. Sustituido por SeaweedFS (§4, enmienda 1 del ADR-0021) |
| **V1:** Supabase Storage por S3 | ⏳ Pendiente de un proyecto real de Supabase |

## 2. V2: el cliente S3 en Java 25

**Montaje.** Proyecto Maven aislado, fuera del repositorio. AWS SDK **2.58.0** (publicada el 11-nov-2026, quince días antes; la 2.59.10 tenía un día) con el BOM del SDK, el módulo `s3` y el cliente HTTP `url-connection-client`, **excluyendo** `netty-nio-client`, `apache-client` y `apache5-client`. JDK 25.0.4. Servidor: SeaweedFS 4.53 (§4) con credenciales obligatorias, para que la firma se valide de verdad.

| Prueba | Resultado |
|---|---|
| `PutObject` con `Content-Type` | ✅ 386 ms el primero (frío) |
| `HeadObject` | ✅ tamaño y tipo correctos |
| URL firmada de 5 min con `response-content-disposition` y `response-content-type` | ✅ `200`, bytes idénticos, `Content-Disposition: attachment; filename="cv.pdf"`, `Content-Type: application/pdf` |
| La misma URL con la firma alterada | ✅ `403` |
| URL firmada de 1 s, pedida a los 3 s | ✅ `403` (caducada) |
| `DeleteObject` y comprobación posterior | ✅ el objeto ya no existe |
| Avisos de la JVM (reflexión, `Unsafe`, acceso nativo) | ✅ ninguno. Solo el aviso de SLF4J por no tener *binder*, que en la aplicación pone Spring Boot |

**Huella de dependencias:** 32 JAR y **8,6 MB**, sin Netty ni Apache. Trae `slf4j-api` 1.7.36 como transitiva, que en el proyecto real resuelve a la versión de Spring Boot. Construir el cliente tarda 848 ms la primera vez, una sola vez por arranque.

**Conclusión:** V2 cumple. Al implementarlo, el SDK entra por su BOM con la versión fijada en el `pom` raíz (ADR-0014) y con las mismas exclusiones.

## 3. V3: ClamAV en la VM de desarrollo

**Montaje.** Imagen oficial `clamav/clamav:1.5.5` (243 MB). La VM tiene 3 vCPU sin AVX y 9,2 GiB de memoria, con el *swap* casi lleno (`08-…` §2.2).

| Medida | Valor |
|---|---|
| Descarga de la imagen | 18 s |
| Hasta responder `PONG` | **27 s**. La imagen trae las firmas principales; al arrancar solo descarga la actualización diaria |
| Memoria en reposo | **≈ 0,96 GiB**, estable |
| Pico al arrancar | 1,22 GiB |
| Pico durante la **recarga de firmas** (`RELOAD`, lo que ocurre con cada actualización diaria) | **1,85 GiB**, durante unos segundos; vuelve a 0,96 GiB y **sigue respondiendo** mientras recarga |
| `INSTREAM` con EICAR | `stream: Eicar-Test-Signature FOUND`, ≈ 60 ms |
| `INSTREAM` con un archivo limpio | `stream: OK`, < 10 ms |
| `INSTREAM` con 5 MB | `stream: OK`, 1 135 ms en frío y 51 ms en caliente |
| Límite de flujo por defecto (`StreamMaxLength`) | 25 MB, de sobra para los 5 MB del ADR |

**El pico de la recarga.** clamd recarga por defecto las firmas nuevas **junto a las viejas** (`ConcurrentDatabaseReload`) para seguir escaneando mientras tanto; por eso la memoria casi se duplica unos segundos. Desactivarlo baja el pico, pero clamd deja de escanear durante la recarga, y con la regla *fail-closed* del ADR (§7) las subidas responderían `503` en ese intervalo. **Se mantiene el valor por defecto y se presupuestan 2 GB para ClamAV.**

**El cliente `INSTREAM` propio** que prevé el ADR (§8) funciona tal como se diseñó: «`zINSTREAM\0`», trozos con su longitud de 4 bytes y un trozo de longitud 0 al final. Son unas 30 líneas.

**Conclusión:** V3 cumple. ClamAV cabe en la VM de desarrollo como servicio opcional del `docker-compose`. No conviene tenerlo siempre encendido junto a Ollama con el *swap* lleno, así que va en un perfil propio. En producción, la decisión R4 («8 GB o más») sigue en pie con el presupuesto corregido: 2 GB para ClamAV.

## 4. Hallazgo: MinIO ya no publica imágenes

El ADR-0021 eligió MinIO para desarrollo y CI. Al preparar V2:

- `docker pull minio/minio:<etiqueta>` responde «*repository does not exist*», y la API de Docker Hub, «*object not found*».
- `quay.io/minio/minio` responde `401`.

MinIO dejó de distribuir imágenes y binarios de su edición comunitaria. Una etiqueta antigua ya no se puede descargar, así que fijar una versión vieja tampoco sirve. El módulo MinIO de Testcontainers que preveía el ADR depende de esas imágenes.

| Sustituto | Valida firmas y URLs firmadas | Licencia | Peso | Valoración |
|---|---|---|---|---|
| **SeaweedFS** (`chrislusf/seaweedfs`) | ✅ Verificado en V2 (firma alterada y caducada, `403`) | Apache 2.0 | 72 MiB en reposo; imagen de 792 MB | **Recomendado**: es el servidor con el que se verificó V2 |
| Adobe S3Mock | No valida credenciales: una prueba de URL firmada pasaría aunque la firma estuviera mal | Apache 2.0 | Ligero | Sirve para pruebas unitarias, no para probar la seguridad de las URLs |
| LocalStack | Sí | Comunitaria con cambios de licencia recientes | Pesado | Más de lo necesario para un solo servicio |

**Decisión (aprobada por el responsable el 26-nov-2026 como enmienda 1 del ADR-0021):** usar **SeaweedFS** en desarrollo y CI con un `GenericContainer` de Testcontainers. El cambio quita una dependencia (el módulo MinIO) y no cambia nada en producción ni en los módulos, porque el adaptador habla S3.

## 5. Cómo repetirlo

El proyecto del spike no se versiona. Se reconstruye con:

1. Un `pom` con el BOM `software.amazon.awssdk:bom` 2.58.0, `s3` (excluyendo `netty-nio-client`, `apache-client` y `apache5-client`) y `url-connection-client`.
2. `docker run -p 8333:8333 chrislusf/seaweedfs:4.53 server -s3 -s3.port=8333 -s3.config=/etc/s3.json -dir=/data`, con un `s3.json` que declare una identidad.
3. `docker run -p 3310:3310 clamav/clamav:1.5.5`; esperar `PONG` y mandar `zRELOAD\0` para ver el pico.

Las mediciones de tiempo en esta VM sirven como orden de magnitud, no como referencia de producción: no tiene AVX y sufre congelamientos (`08-…` §2.2).
