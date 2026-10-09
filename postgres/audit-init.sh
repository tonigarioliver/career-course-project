#!/bin/bash
# Fase 4: audit-service's own database + user in the same Postgres instance as booking-service —
# logically isolated (it can only log into slotwise_audit, never read slotwise's tables) without a
# second container. Runs once, only on a fresh data dir (docker-entrypoint-initdb.d convention);
# on an existing volume create it once by hand:
#   docker compose exec postgres bash /docker-entrypoint-initdb.d/audit-init.sh
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE slotwise_audit WITH LOGIN PASSWORD 'slotwise_audit';
    CREATE DATABASE slotwise_audit OWNER slotwise_audit;
EOSQL
