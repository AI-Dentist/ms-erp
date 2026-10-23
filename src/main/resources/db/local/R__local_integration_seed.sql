-- =========================================================================================================
-- LOCAL INTEGRATION SEED -- SYNTHETIC DATA ONLY. NEVER RUNS IN PRODUCTION.
--
-- This script lives in classpath:db/local, a Flyway location that only the `local` profile adds to
-- spring.flyway.locations. The default location (classpath:db/migration) never sees it.
--
-- Contract:
--  * Idempotent: every statement is INSERT ... ON CONFLICT DO NOTHING with fixed UUIDs in the a?000000-... range.
--    There is no DELETE, TRUNCATE or UPDATE, and the V2 seed rows are never touched (only referenced).
--  * Repeatable (R__): Flyway re-runs it when this file's checksum changes; re-running changes nothing.
--  * Time-relative values (CURRENT_DATE, now()) are evaluated at FIRST load; ON CONFLICT keeps the first values.
--    To refresh "this week" dates on a long-lived local database, recreate the local database (never done here).
--  * Passwords: "LocalDev2026!" hashed with bcrypt via pgcrypto (salt chosen at first load).
--  * Receipt number "LOCAL-REC-000001" cannot collide with the runtime format "REC-<year>-<sequence>" (V3 sequence),
--    so recibo_numero_seq is left untouched and real payments keep numbering from 1.
--
-- ID map (type prefix): a1 usuarios, a2 especialidades, a3 odontologos, a4 consultorios, a5 pacientes,
-- a6 servicios, a7 ordenes_cobro, a8 orden_cobro_items, a9 cuotas_pago, aa movimientos_caja, ab recibos.
-- See docs/local-integration-data.md.
-- =========================================================================================================

-- Users (company config already seeded by V2; the domain reads a single row, so none is added here)
INSERT INTO usuarios (id, rol_id, username, password_hash, email, nombre_completo, activo) VALUES
    ('a1000000-0000-0000-0000-000000000001', (SELECT id FROM roles WHERE codigo = 'ADMIN'), 'admin-scz',
     crypt('LocalDev2026!', gen_salt('bf', 10)), 'admin-scz@local.invalid', 'Admin Local Sintético', TRUE),
    ('a1000000-0000-0000-0000-000000000002', (SELECT id FROM roles WHERE codigo = 'CAJERO'), 'caja-scz',
     crypt('LocalDev2026!', gen_salt('bf', 10)), 'caja-scz@local.invalid', 'Cajero Local Sintético', TRUE),
    ('a1000000-0000-0000-0000-000000000003', (SELECT id FROM roles WHERE codigo = 'RECEPCIONISTA'), 'recepcion-scz',
     crypt('LocalDev2026!', gen_salt('bf', 10)), 'recepcion-scz@local.invalid', 'Recepción Local Sintética', TRUE)
ON CONFLICT DO NOTHING;

-- Master catalogs
INSERT INTO especialidades (id, codigo, nombre, descripcion) VALUES
    ('a2000000-0000-0000-0000-000000000001', 'LOC-ODG', 'Odontología General (local)', 'Especialidad sintética para pruebas locales'),
    ('a2000000-0000-0000-0000-000000000002', 'LOC-CIR', 'Cirugía Bucal (local)', 'Especialidad sintética para pruebas locales')
ON CONFLICT DO NOTHING;

INSERT INTO odontologos (id, usuario_id, ci, matricula_profesional, nombres, apellidos, telefono, email, porcentaje_comision) VALUES
    ('a3000000-0000-0000-0000-000000000001', NULL, 'LOCAL-OD-0001', 'LOCAL-MAT-0001', 'Odontóloga', 'Prueba Cruceña',
     '+59170000101', 'odontologa.prueba@local.invalid', 35.00),
    ('a3000000-0000-0000-0000-000000000002', NULL, 'LOCAL-OD-0002', 'LOCAL-MAT-0002', 'Odontólogo', 'Prueba Camba',
     '+59170000102', 'odontologo.prueba@local.invalid', 30.00)
ON CONFLICT DO NOTHING;

INSERT INTO odontologo_especialidades (odontologo_id, especialidad_id) VALUES
    ('a3000000-0000-0000-0000-000000000001', 'a2000000-0000-0000-0000-000000000001'),
    ('a3000000-0000-0000-0000-000000000002', 'a2000000-0000-0000-0000-000000000001'),
    ('a3000000-0000-0000-0000-000000000002', 'a2000000-0000-0000-0000-000000000002')
ON CONFLICT DO NOTHING;

INSERT INTO consultorios (id, codigo, nombre, ubicacion, estado) VALUES
    ('a4000000-0000-0000-0000-000000000001', 'LOC-C01', 'Consultorio Local 1', 'Planta baja (sintético)', 'DISPONIBLE'),
    ('a4000000-0000-0000-0000-000000000002', 'LOC-C02', 'Consultorio Local 2', 'Primer piso (sintético)', 'DISPONIBLE')
ON CONFLICT DO NOTHING;

INSERT INTO pacientes (id, ci_nit, nombres, apellidos, fecha_nacimiento, genero, telefono_whatsapp, email, direccion) VALUES
    ('a5000000-0000-0000-0000-000000000001', 'LOCAL-0001', 'Ximena Sintética', 'Cruceña Uno', '1990-01-01', 'FEMENINO',
     '+59170000001', 'paciente1@local.invalid', 'Equipetrol (dato ficticio)'),
    ('a5000000-0000-0000-0000-000000000002', 'LOCAL-0002', 'Rodrigo Sintético', 'Camba Dos', '1985-02-02', 'MASCULINO',
     '+59170000002', 'paciente2@local.invalid', 'Plan 3000 (dato ficticio)'),
    ('a5000000-0000-0000-0000-000000000003', 'LOCAL-0003', 'Marisol Sintética', 'Tres Prueba', '1978-03-03', 'FEMENINO',
     '+59170000003', 'paciente3@local.invalid', 'Urbarí (dato ficticio)'),
    ('a5000000-0000-0000-0000-000000000004', 'LOCAL-0004', 'Gonzalo Sintético', 'Cuatro Prueba', '2000-04-04', 'MASCULINO',
     '+59170000004', 'paciente4@local.invalid', 'Villa 1ro de Mayo (dato ficticio)'),
    ('a5000000-0000-0000-0000-000000000005', 'LOCAL-0005', 'Paola Sintética', 'Cinco Prueba', '2012-05-05', 'FEMENINO',
     '+59170000005', NULL, 'Sirari (dato ficticio)')
ON CONFLICT DO NOTHING;

INSERT INTO servicios_odontologicos (id, especialidad_id, codigo, nombre, descripcion, precio_base_bs) VALUES
    ('a6000000-0000-0000-0000-000000000001', 'a2000000-0000-0000-0000-000000000002', 'LOC-CIR-3MR',
     'Exodoncia de Tercer Molar Retenido', 'Cirugía de tercer molar retenido (sintético)', 1250.00),
    ('a6000000-0000-0000-0000-000000000002', 'a2000000-0000-0000-0000-000000000001', 'LOC-ODG-RFR',
     'Restauración Fotopolimerizable con Resina', 'Restauración por pieza con resina fotocurable (sintético)', 280.00),
    ('a6000000-0000-0000-0000-000000000003', 'a2000000-0000-0000-0000-000000000001', 'LOC-ODG-PRF',
     'Profilaxis Dental Local', 'Limpieza y pulido (sintético)', 180.00)
ON CONFLICT DO NOTHING;

-- Orders. Every order has a plan (the domain always creates at least one CONTADO cuota), so the "plan to configure"
-- order (c) is a PENDIENTE CONTADO order with its single default cuota and no payment, which configurarPlanDePagos may rebuild.
INSERT INTO ordenes_cobro (id, paciente_id, odontologo_id, modalidad_pago, monto_subtotal_bs, descuento_bs, monto_total_bs,
                           saldo_pendiente_bs, cantidad_cuotas, estado, observaciones) VALUES
    ('a7000000-0000-0000-0000-000000000001', 'a5000000-0000-0000-0000-000000000001', 'a3000000-0000-0000-0000-000000000001',
     'CONTADO', 280.00, 0.00, 280.00, 280.00, 1, 'PENDIENTE', '[LOCAL] Orden al contado para prueba de cobro en efectivo'),
    ('a7000000-0000-0000-0000-000000000002', 'a5000000-0000-0000-0000-000000000002', 'a3000000-0000-0000-0000-000000000002',
     'CREDITO', 1250.00, 250.00, 1000.00, 1000.00, 3, 'PENDIENTE', '[LOCAL] Crédito en 3 cuotas para prueba de QR'),
    ('a7000000-0000-0000-0000-000000000003', 'a5000000-0000-0000-0000-000000000003', 'a3000000-0000-0000-0000-000000000001',
     'CONTADO', 560.00, 0.00, 560.00, 560.00, 1, 'PENDIENTE', '[LOCAL] Orden sin pagos para probar configurarPlanDePagos'),
    ('a7000000-0000-0000-0000-000000000004', 'a5000000-0000-0000-0000-000000000004', 'a3000000-0000-0000-0000-000000000002',
     'CREDITO', 1250.00, 350.00, 900.00, 450.00, 2, 'PARCIAL', '[LOCAL] Crédito con la primera cuota ya pagada')
ON CONFLICT DO NOTHING;

INSERT INTO orden_cobro_items (id, orden_cobro_id, servicio_id, pieza_dental, cantidad, precio_unitario_bs, subtotal_bs) VALUES
    ('a8000000-0000-0000-0000-000000000001', 'a7000000-0000-0000-0000-000000000001', 'a6000000-0000-0000-0000-000000000002', '16', 1, 280.00, 280.00),
    ('a8000000-0000-0000-0000-000000000002', 'a7000000-0000-0000-0000-000000000002', 'a6000000-0000-0000-0000-000000000001', '38', 1, 1250.00, 1250.00),
    ('a8000000-0000-0000-0000-000000000003', 'a7000000-0000-0000-0000-000000000003', 'a6000000-0000-0000-0000-000000000002', '26', 2, 280.00, 560.00),
    ('a8000000-0000-0000-0000-000000000004', 'a7000000-0000-0000-0000-000000000004', 'a6000000-0000-0000-0000-000000000001', '48', 1, 1250.00, 1250.00)
ON CONFLICT DO NOTHING;

-- Installments: 1000.00 / 3 = 333.33 + 333.33 + 333.34 (remainder cent on the last, like Money.splitInto)
INSERT INTO cuotas_pago (id, orden_cobro_id, numero_cuota, monto_cuota_bs, fecha_vencimiento, estado, fecha_pago) VALUES
    ('a9000000-0000-0000-0000-000000000001', 'a7000000-0000-0000-0000-000000000001', 1, 280.00, CURRENT_DATE, 'PENDIENTE', NULL),
    ('a9000000-0000-0000-0000-000000000002', 'a7000000-0000-0000-0000-000000000002', 1, 333.33, CURRENT_DATE, 'PENDIENTE', NULL),
    ('a9000000-0000-0000-0000-000000000003', 'a7000000-0000-0000-0000-000000000002', 2, 333.33, CURRENT_DATE + 30, 'PENDIENTE', NULL),
    ('a9000000-0000-0000-0000-000000000004', 'a7000000-0000-0000-0000-000000000002', 3, 333.34, CURRENT_DATE + 60, 'PENDIENTE', NULL),
    ('a9000000-0000-0000-0000-000000000005', 'a7000000-0000-0000-0000-000000000003', 1, 560.00, CURRENT_DATE, 'PENDIENTE', NULL),
    ('a9000000-0000-0000-0000-000000000006', 'a7000000-0000-0000-0000-000000000004', 1, 450.00, CURRENT_DATE, 'PAGADA', now()),
    ('a9000000-0000-0000-0000-000000000007', 'a7000000-0000-0000-0000-000000000004', 2, 450.00, CURRENT_DATE + 15, 'PENDIENTE', NULL)
ON CONFLICT DO NOTHING;

-- Cash movements. The paid cuota of order (d) has its INGRESO + receipt; the rest are standalone report data.
-- week_start is Monday 00:00 America/La_Paz (same week definition as report_income). "This week" rows are
-- GREATEST(week_start + k minutes, now() - k days): spread over the week but never before it. "Last week" rows sit
-- k days before week_start (+10h), i.e. inside the previous week for k = 1..5.
WITH w AS (
    SELECT date_trunc('week', now() AT TIME ZONE 'America/La_Paz') AT TIME ZONE 'America/La_Paz' AS week_start
)
INSERT INTO movimientos_caja (id, cuota_id, usuario_cajero_id, tipo_movimiento, categoria, metodo_pago, monto_bs, descripcion, fecha_movimiento)
SELECT v.id, v.cuota_id, 'a1000000-0000-0000-0000-000000000002', v.tipo, v.categoria, v.metodo, v.monto, v.descripcion,
       CASE v.rel
           WHEN 'NOW' THEN now()
           WHEN 'THIS' THEN GREATEST(w.week_start + v.k * interval '1 minute', now() - v.k * interval '1 day')
           ELSE w.week_start - v.k * interval '1 day' + interval '10 hours'
       END
FROM w, (VALUES
    ('aa000000-0000-0000-0000-000000000001'::uuid, 'a9000000-0000-0000-0000-000000000006'::uuid, 'INGRESO', 'COBRO_CUOTA', 'EFECTIVO', 450.00,
     '[LOCAL] Cuota 1/2 de la orden parcial en efectivo', 'NOW', 0),
    ('aa000000-0000-0000-0000-000000000002'::uuid, NULL::uuid, 'INGRESO', 'COBRO_SERVICIO', 'EFECTIVO', 150.00,
     '[LOCAL] Consulta de diagnóstico en efectivo', 'THIS', 1),
    ('aa000000-0000-0000-0000-000000000003'::uuid, NULL::uuid, 'INGRESO', 'COBRO_SERVICIO', 'QR_PAGOFACIL', 520.00,
     '[LOCAL] Restauraciones pagadas por QR', 'THIS', 2),
    ('aa000000-0000-0000-0000-000000000004'::uuid, NULL::uuid, 'INGRESO', 'COBRO_SERVICIO', 'EFECTIVO', 280.00,
     '[LOCAL] Restauración en efectivo', 'THIS', 3),
    ('aa000000-0000-0000-0000-000000000005'::uuid, NULL::uuid, 'INGRESO', 'COBRO_SERVICIO', 'QR_PAGOFACIL', 200.00,
     '[LOCAL] Profilaxis pagada por QR', 'THIS', 4),
    ('aa000000-0000-0000-0000-000000000006'::uuid, NULL::uuid, 'EGRESO', 'INSUMOS', 'EFECTIVO', 175.50,
     '[LOCAL] Compra de insumos descartables', 'THIS', 2),
    ('aa000000-0000-0000-0000-000000000007'::uuid, NULL::uuid, 'EGRESO', 'SERVICIOS_BASICOS', 'TRANSFERENCIA', 320.00,
     '[LOCAL] Pago de energía eléctrica', 'THIS', 3),
    ('aa000000-0000-0000-0000-000000000008'::uuid, NULL::uuid, 'INGRESO', 'COBRO_SERVICIO', 'EFECTIVO', 360.00,
     '[LOCAL] Tratamiento semana pasada en efectivo', 'LAST', 1),
    ('aa000000-0000-0000-0000-000000000009'::uuid, NULL::uuid, 'INGRESO', 'COBRO_SERVICIO', 'QR_PAGOFACIL', 640.00,
     '[LOCAL] Tratamiento semana pasada por QR', 'LAST', 2),
    ('aa000000-0000-0000-0000-000000000010'::uuid, NULL::uuid, 'EGRESO', 'ALQUILER', 'TRANSFERENCIA', 1500.00,
     '[LOCAL] Alquiler semana pasada', 'LAST', 3)
) AS v(id, cuota_id, tipo, categoria, metodo, monto, descripcion, rel, k)
ON CONFLICT DO NOTHING;

INSERT INTO recibos (id, movimiento_caja_id, numero_recibo, ci_nit_cliente, razon_social_cliente, concepto, monto_total_bs, fecha_emision)
SELECT 'ab000000-0000-0000-0000-000000000001', m.id, 'LOCAL-REC-000001', 'LOCAL-0004', 'Gonzalo Sintético Cuatro Prueba',
       m.descripcion, m.monto_bs, m.fecha_movimiento
FROM movimientos_caja m
WHERE m.id = 'aa000000-0000-0000-0000-000000000001'
ON CONFLICT DO NOTHING;
