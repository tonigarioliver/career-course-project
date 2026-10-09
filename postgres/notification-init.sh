#!/bin/bash
# Fase 4: notification-service's own database + user in the same Postgres instance (same idea as
# audit-init.sh). Runs once, only on a fresh data dir; on an existing volume create it once by hand:
#   docker compose exec postgres bash /docker-entrypoint-initdb.d/notification-init.sh
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE slotwise_notification WITH LOGIN PASSWORD 'slotwise_notification';
    CREATE DATABASE slotwise_notification OWNER slotwise_notification;
    -- Postgres grants CONNECT to PUBLIC by default; without this any other service's role could log into this database.
    REVOKE CONNECT ON DATABASE slotwise_notification FROM PUBLIC;
EOSQL
