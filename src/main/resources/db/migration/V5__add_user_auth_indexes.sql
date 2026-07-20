-- Performance indexes for authentication queries
CREATE INDEX IF NOT EXISTS idx_users_enabled ON users (username, enabled);
CREATE INDEX IF NOT EXISTS idx_authorities_username ON authorities (username);
