CREATE TABLE app_users (
  id UUID PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL CHECK (role IN ('USER','ADMIN'))
);
CREATE TABLE jobs (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES app_users(id),
  type VARCHAR(32) NOT NULL CHECK (type IN ('CSV_SUMMARY','RECORD_VALIDATION')),
  input TEXT NOT NULL CHECK (octet_length(input) <= 65536),
  status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','RUNNING','RETRY_PENDING','SUCCEEDED','FAILED','CANCELLED')),
  created_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  retry_limit INTEGER NOT NULL CHECK (retry_limit BETWEEN 0 AND 3),
  next_execution_at TIMESTAMPTZ NOT NULL,
  lease_until TIMESTAMPTZ,
  lease_token UUID,
  cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
  failure_code VARCHAR(40),
  result TEXT CHECK (octet_length(result) <= 65536),
  result_bytes INTEGER,
  idempotency_key VARCHAR(128) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL CHECK (length(fingerprint) = 64),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (owner_id, idempotency_key),
  CHECK (attempt_count <= retry_limit + 1),
  CHECK ((status = 'RUNNING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
      OR (status <> 'RUNNING' AND lease_until IS NULL AND lease_token IS NULL)),
  CHECK ((status IN ('SUCCEEDED','FAILED','CANCELLED')) = (completed_at IS NOT NULL)),
  CHECK ((status = 'SUCCEEDED' AND result IS NOT NULL AND result_bytes IS NOT NULL AND result_bytes = octet_length(result))
      OR (status <> 'SUCCEEDED' AND result IS NULL AND result_bytes IS NULL))
);
CREATE INDEX jobs_owner_created ON jobs(owner_id, created_at DESC, id);
CREATE INDEX jobs_eligible ON jobs(next_execution_at, created_at, id) WHERE status IN ('PENDING','RETRY_PENDING');
CREATE INDEX jobs_leases ON jobs(lease_until) WHERE status = 'RUNNING';
CREATE INDEX jobs_admin_created ON jobs(created_at DESC, id);
CREATE TABLE execution_attempts (
  id UUID PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES jobs(id),
  number INTEGER NOT NULL CHECK (number > 0),
  lease_token UUID NOT NULL UNIQUE,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('RUNNING','SUCCEEDED','FAILED','ABANDONED','CANCELLED')),
  failure_code VARCHAR(40),
  UNIQUE(job_id, number),
  CHECK ((outcome = 'RUNNING') = (completed_at IS NULL))
);
CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES jobs(id),
  actor_id UUID REFERENCES app_users(id),
  event VARCHAR(40) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX audit_job_time ON audit_events(job_id, occurred_at);
