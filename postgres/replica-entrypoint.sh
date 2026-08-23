#!/bin/bash
# Custom entrypoint for the replica container — the official postgres image has no
# env-var knob for "clone yourself from another Postgres and follow its WAL", so this
# does by hand what a managed Postgres (RDS read replica, etc.) does under the hood:
#
# 1. pg_basebackup: a full physical (byte-for-byte) copy of the primary's data
#    directory, taken over the replication protocol — NOT `pg_dump`/`pg_restore` (those
#    are logical, per-object SQL, and can't be the starting point for physically
#    replaying WAL against). This has to happen before the replica can apply any WAL,
#    since WAL records are physical diffs against a known-identical starting state.
# 2. `-R` writes postgresql.auto.conf with `primary_conninfo` (how to reconnect and keep
#    streaming) AND creates `standby.signal` — an empty marker file whose mere presence
#    is what tells Postgres on startup "come up in hot standby / recovery mode, don't
#    become a normal read-write primary". No signal file, no PITR config: this is the
#    entire difference between "a copy of a database" and "a replica of a database".
#
# Only runs the clone once: on every later container restart the data directory is
# already populated (and already has standby.signal from the first `-R` clone), so this
# skips straight to starting postgres, which resumes streaming from where it left off.
set -e

if [ -z "$(ls -A "$PGDATA" 2>/dev/null)" ]; then
    echo "replica: empty data directory, cloning from primary via pg_basebackup"
    until PGPASSWORD=replicator pg_basebackup -h postgres -U replicator -D "$PGDATA" -Fp -Xs -P -R; do
        echo "replica: primary not ready yet, retrying in 2s"
        sleep 2
    done
    chmod 700 "$PGDATA"
fi

exec docker-entrypoint.sh postgres
