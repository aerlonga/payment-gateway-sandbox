ALTER TABLE payments
    ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0;

-- pagamentos ja estornados (modelo antigo: todo estorno virava REFUNDED)
UPDATE payments SET refunded_amount = amount WHERE status = 'REFUNDED';

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_refunded_amount CHECK (refunded_amount >= 0 AND refunded_amount <= amount);
