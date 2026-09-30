ALTER TABLE processed_messages ALTER COLUMN processed_at DROP NOT NULL;
ALTER TABLE processed_messages ADD COLUMN status varchar(16) NOT NULL DEFAULT 'Processed';
ALTER TABLE processed_messages ADD COLUMN owner uuid;
ALTER TABLE processed_messages ADD COLUMN locked_until timestamptz;
ALTER TABLE processed_messages ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE processed_messages ADD COLUMN last_error varchar(500);

ALTER TABLE webhook_events ADD COLUMN owner uuid;
ALTER TABLE webhook_events ADD COLUMN locked_until timestamptz;
ALTER TABLE webhook_events ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX webhook_claimable ON webhook_events(status,next_attempt_at,locked_until);

ALTER TABLE outbox_messages ADD COLUMN owner uuid;
