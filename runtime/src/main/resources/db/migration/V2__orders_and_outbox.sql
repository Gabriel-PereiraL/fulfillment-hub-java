CREATE SEQUENCE order_number_seq START WITH 1000;

CREATE TABLE products (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0,
 sku varchar(32) NOT NULL UNIQUE, name varchar(120) NOT NULL,
 unit_price_amount numeric(18,2) NOT NULL, unit_price_currency varchar(3) NOT NULL,
 stock_quantity integer NOT NULL CHECK (stock_quantity >= 0), active boolean NOT NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 CONSTRAINT products_price_positive CHECK (unit_price_amount > 0)
);
CREATE INDEX products_active ON products(active);

CREATE TABLE orders (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0,
 number bigint NOT NULL UNIQUE DEFAULT nextval('order_number_seq'), customer_id uuid NOT NULL,
 status varchar(32) NOT NULL, cancellation_reason varchar(32), payment_id uuid, delivery_id uuid,
 idempotency_key varchar(64), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 delivery_address_street varchar(200) NOT NULL, delivery_address_number varchar(20) NOT NULL,
 delivery_address_complement varchar(100), delivery_address_district varchar(100) NOT NULL,
 delivery_address_city varchar(100) NOT NULL, delivery_address_state varchar(50) NOT NULL,
 delivery_address_postal_code varchar(10) NOT NULL, delivery_address_country varchar(2) NOT NULL,
 delivery_address_latitude double precision, delivery_address_longitude double precision,
 subtotal_amount numeric(18,2) NOT NULL, subtotal_currency varchar(3) NOT NULL,
 delivery_fee_amount numeric(18,2), delivery_fee_currency varchar(3),
 total_amount numeric(18,2) NOT NULL, total_currency varchar(3) NOT NULL,
 CONSTRAINT orders_coordinates_paired CHECK ((delivery_address_latitude IS NULL) = (delivery_address_longitude IS NULL))
);
CREATE INDEX orders_customer_created ON orders(customer_id, created_at DESC);
CREATE INDEX orders_status ON orders(status);

CREATE TABLE order_items (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
 product_id uuid NOT NULL, sku varchar(32) NOT NULL, product_name varchar(120) NOT NULL,
 unit_price_amount numeric(18,2) NOT NULL, unit_price_currency varchar(3) NOT NULL,
 quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
 UNIQUE(order_id, product_id)
);
CREATE INDEX order_items_product ON order_items(product_id);

CREATE TABLE order_status_changes (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
 from_status varchar(32) NOT NULL, to_status varchar(32) NOT NULL,
 occurred_at timestamptz NOT NULL, reason varchar(200), actor_user_id uuid
);

CREATE TABLE idempotency_records (
 scope varchar(64) NOT NULL, key varchar(64) NOT NULL, version bigint NOT NULL DEFAULT 0,
 request_hash varchar(64) NOT NULL, status varchar(16) NOT NULL,
 response_status_code integer, response_body text, response_content_type varchar(128), response_location varchar(512),
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 PRIMARY KEY(scope, key)
);
CREATE INDEX idempotency_expiry ON idempotency_records(expires_at);

CREATE TABLE outbox_messages (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, type varchar(100) NOT NULL,
 payload text NOT NULL, aggregate_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL, status varchar(16) NOT NULL, attempts integer NOT NULL,
 next_attempt_at timestamptz NOT NULL, locked_until timestamptz, processed_at timestamptz,
 last_error varchar(1000), correlation_id varchar(64), trace_parent varchar(128)
);
CREATE INDEX outbox_pending ON outbox_messages(status, next_attempt_at);
CREATE INDEX outbox_aggregate ON outbox_messages(aggregate_id);
