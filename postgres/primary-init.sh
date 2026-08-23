#!/bin/bash
# Runs once, automatically, the first time the primary's data directory is initialized
# (docker-entrypoint-initdb.d convention — the official postgres image runs every .sh/.sql
# file in there right after `initdb`, but ONLY on a fresh/empty data dir, never again on
# a restart). Sets up exactly what streaming replication needs on the primary side:
#
# 1. A dedicated role with the REPLICATION privilege. This is deliberately not the app's
#    `slotwise` user — REPLICATION is its own privilege bit (separate from
#    SUPERUSER/CREATEDB/etc.), and a replica connecting with it doesn't run SQL, it runs
#    the replication protocol (`START_REPLICATION`) — a real permission boundary, not a
#    convention.
# 2. A pg_hba.conf line allowing that role to open a `replication`-type connection.
#    pg_hba.conf's "replication" is a distinct pseudo-database, not a real one — it's how
#    Postgres' own auth config distinguishes "wants to run SQL against a database" from
#    "wants to stream WAL".
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replicator';
EOSQL

echo "host replication replicator 0.0.0.0/0 md5" >> "$PGDATA/pg_hba.conf"
