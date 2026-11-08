-- Additive tariff classification; legacy services remain unclassified.
ALTER TABLE servicios_odontologicos ADD COLUMN categoria VARCHAR(100);
