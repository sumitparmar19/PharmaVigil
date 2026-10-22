#!/bin/bash
# Oracle init script for docker compose: one schema per service (they share table names such as outbox_event).
# Runs once, when the database volume is created. The password comes from PV_DB_PASSWORD (.env); passwords
# go to sqlplus on stdin, never on its command line.
set -euo pipefail
for user in pv_pharmacy pv_intake pv_case pv_signal pv_submission; do
  sqlplus -s /nolog <<SQL
WHENEVER SQLERROR EXIT FAILURE
CONNECT system/"${ORACLE_PASSWORD}"@//localhost/FREEPDB1
CREATE USER ${user} IDENTIFIED BY "${PV_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO ${user};
EXIT;
SQL
done
