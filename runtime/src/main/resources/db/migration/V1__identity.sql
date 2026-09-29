CREATE TABLE users (
 id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0,
 email varchar(254) NOT NULL UNIQUE, password_hash varchar(512) NOT NULL,
 active boolean NOT NULL, security_version uuid NOT NULL, roles text[] NOT NULL,
 created_at timestamptz NOT NULL, last_login_at timestamptz, customer_id uuid,
 CONSTRAINT users_roles_nonempty CHECK (cardinality(roles) > 0)
);
CREATE TABLE auth_sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 security_version uuid NOT NULL, expires_at timestamptz NOT NULL, revoked_at timestamptz
);
CREATE INDEX auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX auth_sessions_expiry ON auth_sessions(expires_at);
CREATE TABLE refresh_credentials (
 hash varchar(64) PRIMARY KEY, session_id uuid NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
 used_at timestamptz
);
CREATE INDEX refresh_credentials_session ON refresh_credentials(session_id);
