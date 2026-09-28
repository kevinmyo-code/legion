"""Deterministic statement parsers for `drive_statements` (backend-etl ticket 10).

Each one registers with `ingest.statements.register_parser` from
`IngestConfig.ready`, and is tried before Gemini.
"""
