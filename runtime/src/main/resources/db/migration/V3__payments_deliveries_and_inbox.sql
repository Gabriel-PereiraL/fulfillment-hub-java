CREATE TABLE customers (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, user_id uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
 name varchar(120) NOT NULL, email varchar(254) NOT NULL UNIQUE, phone varchar(16) NOT NULL,
 active boolean NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE customer_addresses (
 id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
 label varchar(40) NOT NULL, is_default boolean NOT NULL,
 street varchar(200) NOT NULL, number varchar(20) NOT NULL, complement varchar(100), district varchar(100) NOT NULL,
 city varchar(100) NOT NULL, state varchar(50) NOT NULL, postal_code varchar(10) NOT NULL, country varchar(2) NOT NULL,
 latitude double precision, longitude double precision,
 UNIQUE(customer_id,label), CONSTRAINT customer_address_coordinates_paired CHECK ((latitude IS NULL)=(longitude IS NULL))
);

CREATE TABLE payments (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, order_id uuid NOT NULL,
 status varchar(32) NOT NULL, provider varchar(40) NOT NULL, provider_payment_id varchar(128),
 provider_idempotency_key varchar(128) NOT NULL UNIQUE, failure_reason varchar(200), last_provider_event_at timestamptz,
 amount_amount numeric(18,2) NOT NULL, amount_currency varchar(3) NOT NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(provider,provider_payment_id)
);
CREATE UNIQUE INDEX payments_one_active_per_order ON payments(order_id) WHERE status NOT IN ('Failed','Cancelled');
CREATE INDEX payments_status ON payments(status);
CREATE TABLE payment_attempts (
 id uuid PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
 attempt_number integer NOT NULL, started_at timestamptz NOT NULL, completed_at timestamptz,
 outcome varchar(32), provider_reference varchar(128), error_code varchar(64), UNIQUE(payment_id,attempt_number)
);
CREATE UNIQUE INDEX payment_one_pending_attempt ON payment_attempts(payment_id) WHERE completed_at IS NULL;

CREATE TABLE delivery_quotes (
 id uuid PRIMARY KEY, order_id uuid NOT NULL, provider varchar(40) NOT NULL, provider_quote_id varchar(128) NOT NULL,
 fee_amount numeric(18,2) NOT NULL, fee_currency varchar(3) NOT NULL,
 estimated_dropoff_at timestamptz NOT NULL, duration_minutes integer NOT NULL, pickup_duration_minutes integer NOT NULL,
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL, UNIQUE(provider,provider_quote_id)
);
CREATE INDEX delivery_quotes_order ON delivery_quotes(order_id);
CREATE TABLE deliveries (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, order_id uuid NOT NULL, quote_id uuid NOT NULL,
 provider varchar(40) NOT NULL, provider_delivery_id varchar(128), provider_idempotency_key varchar(128) NOT NULL UNIQUE,
 status varchar(32) NOT NULL, fee_amount numeric(18,2) NOT NULL, fee_currency varchar(3) NOT NULL,
 tracking_url varchar(512), last_provider_event_at timestamptz,
 courier_name varchar(120), courier_phone_masked varchar(16), courier_vehicle_type varchar(40),
 courier_latitude double precision, courier_longitude double precision,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, UNIQUE(provider,provider_delivery_id)
);
CREATE UNIQUE INDEX deliveries_one_active_per_order ON deliveries(order_id) WHERE status NOT IN ('Cancelled','Delivered','Returned');
CREATE INDEX deliveries_status ON deliveries(status);
CREATE TABLE delivery_events (
 id uuid PRIMARY KEY, delivery_id uuid NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
 provider_event_id varchar(128) NOT NULL, provider_status varchar(40) NOT NULL,
 occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL, disposition varchar(32) NOT NULL,
 UNIQUE(delivery_id,provider_event_id)
);

CREATE TABLE webhook_events (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, provider varchar(40) NOT NULL,
 provider_event_id varchar(128) NOT NULL, event_type varchar(100) NOT NULL, payload bytea NOT NULL,
 status varchar(16) NOT NULL, received_at timestamptz NOT NULL, processed_at timestamptz,
 attempts integer NOT NULL DEFAULT 0, last_error varchar(1000), UNIQUE(provider,provider_event_id),
 CONSTRAINT webhook_payload_limit CHECK (octet_length(payload)<=65536)
);
CREATE INDEX webhook_status_received ON webhook_events(status,received_at);
CREATE TABLE processed_messages (
 consumer varchar(100) NOT NULL, message_id varchar(128) NOT NULL, processed_at timestamptz NOT NULL,
 PRIMARY KEY(consumer,message_id)
);
CREATE INDEX processed_messages_age ON processed_messages(processed_at);
