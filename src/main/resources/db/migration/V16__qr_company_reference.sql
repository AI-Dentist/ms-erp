-- Additive correlation only: preserve original ERP amounts and all existing mock rows.
ALTER TABLE transacciones_qr ADD COLUMN company_transaction_id VARCHAR(100);
CREATE UNIQUE INDEX uq_transacciones_qr_company_reference ON transacciones_qr(company_transaction_id);
