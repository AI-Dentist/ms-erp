CREATE TABLE offline_sync_sessions (
    session_id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES usuarios(id),
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    workspace_id UUID NOT NULL,
    device_id UUID NOT NULL,
    reauth_not_before TIMESTAMP WITH TIME ZONE NOT NULL,
    challenge_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    authenticated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    CHECK ((authenticated_at IS NULL) = (expires_at IS NULL))
);
