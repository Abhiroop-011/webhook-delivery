CREATE TABLE webhook_event (
    id uuid PRIMARY KEY,
    ingest_key text NOT NULL UNIQUE,
    destination text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','DELIVERING','DELIVERED','DEAD')),
    attempts_used integer NOT NULL DEFAULT 0 CHECK (attempts_used >= 0),
    total_attempts integer NOT NULL DEFAULT 0 CHECK (total_attempts >= attempts_used),
    replay_count integer NOT NULL DEFAULT 0,
    due_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((state = 'DELIVERING') = (claim_token IS NOT NULL)),
    CHECK ((state = 'DELIVERING') = (lease_until IS NOT NULL))
);

CREATE INDEX pending_due ON webhook_event(due_at, id) WHERE state = 'PENDING';
CREATE INDEX expired_lease ON webhook_event(lease_until, id) WHERE state = 'DELIVERING';
CREATE INDEX event_recency ON webhook_event(created_at DESC, id);

CREATE TABLE delivery_attempt (
    event_id uuid NOT NULL REFERENCES webhook_event(id),
    number integer NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    outcome text NOT NULL DEFAULT 'STARTED' CHECK (outcome IN
        ('STARTED','ACKNOWLEDGED','RETRYABLE','REJECTED','UNKNOWN')),
    http_status integer,
    latency_ms bigint,
    detail text,
    PRIMARY KEY(event_id, number)
);
