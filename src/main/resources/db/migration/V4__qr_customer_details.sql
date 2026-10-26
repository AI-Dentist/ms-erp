-- Persist the receipt identity and the authenticated initiating cashier, not callback-supplied values.
-- Nullable only for legacy transactions: such rows cannot be confirmed by the local mock workflow.
ALTER TABLE transacciones_qr ADD COLUMN ci_nit_cliente VARCHAR(30);
ALTER TABLE transacciones_qr ADD COLUMN razon_social_cliente VARCHAR(150);
ALTER TABLE transacciones_qr ADD COLUMN usuario_cajero_id UUID REFERENCES usuarios(id);
