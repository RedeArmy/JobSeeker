# Esquema oficial de OpenAPI 3.1

Copia sin modificar de los esquemas JSON que publica la OpenAPI Initiative para validar documentos
OpenAPI 3.1 (licencia Apache 2.0). Las pruebas validan contra ellos el *spec* que genera springdoc
(ADR-0010, decisión 4). Están en el repositorio porque las pruebas no deben depender de la red.

| Archivo | Identificador (`$id`) |
|---|---|
| `schema-base-2022-10-07.json` | `https://spec.openapis.org/oas/3.1/schema-base/2022-10-07` |
| `schema-2022-10-07.json` | `https://spec.openapis.org/oas/3.1/schema/2022-10-07` |
| `dialect-base.json` | `https://spec.openapis.org/oas/3.1/dialect/base` |
| `meta-base.json` | `https://spec.openapis.org/oas/3.1/meta/base` |

Se usa `schema-base`, que además valida cada objeto Schema contra el dialecto de OpenAPI. Descargados
el 28-nov-2026. Para actualizarlos, se descargan de nuevo de esos mismos identificadores.
