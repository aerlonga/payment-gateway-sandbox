ALTER TABLE payments
    ADD COLUMN payment_method VARCHAR(30),
    ADD COLUMN description    VARCHAR(255);
