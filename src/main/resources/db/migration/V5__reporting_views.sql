-- Only these projections are approved for reporting. No identifiers, free-text descriptions or PII.
-- Views run with their owner's SELECT privileges; the reporting login gets no base-table access.
CREATE VIEW report_income AS
SELECT (fecha_movimiento AT TIME ZONE 'America/La_Paz')::date AS day,
       categoria AS category, metodo_pago AS payment_method,
       sum(monto_bs) AS amount_bs, count(*) AS movement_count
FROM movimientos_caja
WHERE tipo_movimiento = 'INGRESO'
GROUP BY (fecha_movimiento AT TIME ZONE 'America/La_Paz')::date, categoria, metodo_pago;

CREATE VIEW report_orders AS
SELECT estado AS status, count(*) AS order_count,
       sum(monto_total_bs) AS total_bs, sum(saldo_pendiente_bs) AS balance_bs
FROM ordenes_cobro GROUP BY estado;

CREATE VIEW report_services AS
SELECT codigo AS code, precio_base_bs AS price_bs, activo AS active
FROM servicios_odontologicos;
