ALTER TABLE idempotency_records ADD COLUMN order_id uuid REFERENCES orders(id);
CREATE INDEX idempotency_order ON idempotency_records(order_id) WHERE order_id IS NOT NULL;
