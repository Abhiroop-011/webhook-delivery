CREATE TABLE warehouse_order (
    event_id uuid PRIMARY KEY,
    payload jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
