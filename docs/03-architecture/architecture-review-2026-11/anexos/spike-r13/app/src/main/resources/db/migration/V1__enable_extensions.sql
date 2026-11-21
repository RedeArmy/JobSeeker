-- ADR-0027: las extensiones van en `app`, antes de cualquier tabla (cv y job ya llevan embeddings).
create extension if not exists vector;
