ALTER TABLE webhook_events DROP CONSTRAINT webhook_payload_limit;
ALTER TABLE webhook_events ALTER COLUMN payload TYPE jsonb USING convert_from(payload,'UTF8')::jsonb;
ALTER TABLE webhook_events ALTER COLUMN event_type TYPE varchar(64);
ALTER TABLE webhook_events ALTER COLUMN last_error TYPE varchar(500);
ALTER TABLE webhook_events ADD COLUMN correlation_id varchar(64);
