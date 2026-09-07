CREATE TABLE payments (
    id                   UUID PRIMARY KEY,
    customer_id          UUID NOT NULL,
    amount               BIGINT NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    provider             VARCHAR(30) NOT NULL,
    provider_payment_id  VARCHAR(100),
    idempotency_key      VARCHAR(100) NOT NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_payments_customer_id_status ON payments (customer_id, status);
