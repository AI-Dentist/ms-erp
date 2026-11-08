-- Immutable offline intent: identity, type, canonical version and payload hash are frozen at reservation.
-- Only state/reason/result/confirmation may change, so a replayed operationId can never rewrite its intent.
CREATE OR REPLACE FUNCTION offline_operation_receipts_reject_intent_change() RETURNS trigger AS $$
BEGIN
    IF NEW.identity_issuer IS DISTINCT FROM OLD.identity_issuer
        OR NEW.identity_subject IS DISTINCT FROM OLD.identity_subject
        OR NEW.operation_type IS DISTINCT FROM OLD.operation_type
        OR NEW.canonical_version IS DISTINCT FROM OLD.canonical_version
        OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
        OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
        OR NEW.operation_id IS DISTINCT FROM OLD.operation_id THEN
        RAISE EXCEPTION 'offline operation intent is immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER offline_operation_receipts_immutable_intent
    BEFORE UPDATE ON offline_operation_receipts
    FOR EACH ROW EXECUTE FUNCTION offline_operation_receipts_reject_intent_change();
