CREATE TABLE processed_webhook_events (
    id                UUID PRIMARY KEY,
    stripe_event_id   VARCHAR(100) NOT NULL,
    processed_at      TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT uq_processed_webhook_events_stripe_event_id UNIQUE (stripe_event_id)
);
