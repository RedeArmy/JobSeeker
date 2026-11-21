-- ADR-0027: las extensiones van en `app`, antes de cualquier tabla (cv y job ya llevan columnas
-- `embedding`). pgvector 0.8.x es el mínimo para `hnsw.iterative_scan` (ADR-0002, ADR-0028).
create extension if not exists vector;
