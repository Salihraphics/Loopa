#!/usr/bin/env bash
# Runs the LOOPA backend test-suite against a throw-away local PostgreSQL
# database. Usage:
#   PGHOST=/path/to/socket PGPORT=5432 PGUSER=postgres ./supabase/tests/run_local_tests.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
DB=${LOOPA_TEST_DB:-loopa_test}
psql -v ON_ERROR_STOP=1 -q -d postgres -c "drop database if exists $DB" -c "create database $DB"
psql -v ON_ERROR_STOP=1 -q -d "$DB" -f supabase/tests/local_supabase_stub.sql
for f in supabase/migrations/*.sql; do
  echo "applying $f"
  psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$f" >/dev/null
done
for t in supabase/tests/*_test.sql; do
  echo "running $t"
  psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$t"
done
LOOPA_TEST_DB=$DB ./supabase/tests/promo_concurrency.sh
echo "ALL BACKEND TESTS PASSED"
