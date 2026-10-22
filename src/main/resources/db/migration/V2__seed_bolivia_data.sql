-- V2: seed data for a dental clinic in Santa Cruz de la Sierra, Bolivia. Amounts in Bolivianos (Bs).
-- Fixed UUIDs keep the seed stable for local development and for MS2/MS3 integration tests.

-- -------------------------------------------------------------------------
-- Company configuration
-- -------------------------------------------------------------------------
INSERT INTO empresa_config (id, nombre_comercial, razon_social, nit, direccion, telefono_contacto, email_contacto, moneda_simbolo)
VALUES ('00000000-0000-0000-0000-000000000001',
        'Clínica Dental Sonrisa Cruceña',
        'Sonrisa Cruceña Servicios Odontológicos S.R.L.',
        '1023456029',
        'Av. San Martín #155, Equipetrol Norte, entre 3er y 4to anillo, Santa Cruz de la Sierra',
        '+591 3 3412233',
        'contacto@sonrisacrucena.bo',
        'Bs');

-- -------------------------------------------------------------------------
-- Roles and permissions
-- -------------------------------------------------------------------------
INSERT INTO roles (id, codigo, nombre, descripcion) VALUES
    ('10000000-0000-0000-0000-000000000001', 'ADMIN', 'Administrador', 'Acceso total a configuración, catálogos y finanzas'),
    ('10000000-0000-0000-0000-000000000002', 'CAJERO', 'Cajero', 'Cobro de cuotas, egresos y emisión de recibos'),
    ('10000000-0000-0000-0000-000000000003', 'RECEPCIONISTA', 'Recepcionista', 'Registro de pacientes y consulta de órdenes'),
    ('10000000-0000-0000-0000-000000000004', 'ODONTOLOGO', 'Odontólogo', 'Consulta de pacientes y servicios');

INSERT INTO permisos (id, codigo, modulo, descripcion) VALUES
    ('11000000-0000-0000-0000-000000000001', 'CONFIG_EMPRESA_EDITAR', 'CONFIGURACION', 'Editar datos de la empresa'),
    ('11000000-0000-0000-0000-000000000002', 'USUARIOS_GESTIONAR', 'SEGURIDAD', 'Crear y administrar usuarios'),
    ('11000000-0000-0000-0000-000000000003', 'AUDITORIA_VER', 'SEGURIDAD', 'Consultar bitácora de auditoría'),
    ('11000000-0000-0000-0000-000000000004', 'PACIENTES_GESTIONAR', 'CATALOGOS', 'Registrar y editar pacientes'),
    ('11000000-0000-0000-0000-000000000005', 'CATALOGOS_GESTIONAR', 'CATALOGOS', 'Gestionar odontólogos, consultorios y servicios'),
    ('11000000-0000-0000-0000-000000000006', 'ORDENES_GESTIONAR', 'FINANZAS', 'Crear órdenes de cobro y planes de pago'),
    ('11000000-0000-0000-0000-000000000007', 'CAJA_COBRAR', 'FINANZAS', 'Cobrar cuotas en efectivo o QR'),
    ('11000000-0000-0000-0000-000000000008', 'CAJA_EGRESOS', 'FINANZAS', 'Registrar egresos de caja'),
    ('11000000-0000-0000-0000-000000000009', 'REPORTES_IA', 'REPORTES', 'Generar reportes inteligentes y exportarlos');

-- ADMIN gets every permission
INSERT INTO rol_permisos (rol_id, permiso_id)
SELECT '10000000-0000-0000-0000-000000000001', id FROM permisos;

INSERT INTO rol_permisos (rol_id, permiso_id) VALUES
    ('10000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000006'),
    ('10000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000007'),
    ('10000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000008'),
    ('10000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000004'),
    ('10000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000006'),
    ('10000000-0000-0000-0000-000000000004', '11000000-0000-0000-0000-000000000004');

-- -------------------------------------------------------------------------
-- Users (local password for dev: "Dental2026!", hashed with bcrypt via pgcrypto)
-- -------------------------------------------------------------------------
INSERT INTO usuarios (id, rol_id, username, password_hash, email, nombre_completo) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'admin',
     crypt('Dental2026!', gen_salt('bf', 10)), 'admin@sonrisacrucena.bo', 'Mariela Suárez Vaca'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'caja.roca',
     crypt('Dental2026!', gen_salt('bf', 10)), 'caja@sonrisacrucena.bo', 'Jorge Luis Roca Paz'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 'recepcion.melgar',
     crypt('Dental2026!', gen_salt('bf', 10)), 'recepcion@sonrisacrucena.bo', 'Ana Lucía Melgar Ribera'),
    ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000004', 'dra.roca',
     crypt('Dental2026!', gen_salt('bf', 10)), 'valeria.roca@sonrisacrucena.bo', 'Valeria Roca Antelo'),
    ('20000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000004', 'dr.justiniano',
     crypt('Dental2026!', gen_salt('bf', 10)), 'diego.justiniano@sonrisacrucena.bo', 'Diego Justiniano Cuéllar');

-- -------------------------------------------------------------------------
-- Master catalogs
-- -------------------------------------------------------------------------
INSERT INTO consultorios (id, codigo, nombre, ubicacion, estado) VALUES
    ('30000000-0000-0000-0000-000000000001', 'CONS-01', 'Consultorio 1 - Planta Baja', 'Planta baja, ala izquierda', 'DISPONIBLE'),
    ('30000000-0000-0000-0000-000000000002', 'CONS-02', 'Consultorio 2 - Planta Baja', 'Planta baja, ala derecha', 'DISPONIBLE'),
    ('30000000-0000-0000-0000-000000000003', 'CONS-03', 'Consultorio 3 - Ortodoncia', 'Primer piso', 'DISPONIBLE'),
    ('30000000-0000-0000-0000-000000000004', 'RX-01', 'Sala de Radiología', 'Primer piso, junto a esterilización', 'MANTENIMIENTO');

INSERT INTO especialidades (id, codigo, nombre, descripcion) VALUES
    ('40000000-0000-0000-0000-000000000001', 'ODG', 'Odontología General', 'Diagnóstico, profilaxis y restauraciones simples'),
    ('40000000-0000-0000-0000-000000000002', 'ORT', 'Ortodoncia', 'Corrección de maloclusiones con brackets y alineadores'),
    ('40000000-0000-0000-0000-000000000003', 'END', 'Endodoncia', 'Tratamiento de conductos radiculares'),
    ('40000000-0000-0000-0000-000000000004', 'EST', 'Estética Dental', 'Blanqueamiento y carillas'),
    ('40000000-0000-0000-0000-000000000005', 'CIR', 'Cirugía Bucal', 'Extracciones complejas y terceros molares');

INSERT INTO odontologos (id, usuario_id, ci, matricula_profesional, nombres, apellidos, telefono, email, porcentaje_comision) VALUES
    ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', '5832147 SC', 'COL-OD-4512',
     'Valeria', 'Roca Antelo', '+59170811223', 'valeria.roca@sonrisacrucena.bo', 35.00),
    ('50000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000005', '6214598 SC', 'COL-OD-3877',
     'Diego', 'Justiniano Cuéllar', '+59176054410', 'diego.justiniano@sonrisacrucena.bo', 30.00),
    ('50000000-0000-0000-0000-000000000003', NULL, '4471290 SC', 'COL-OD-2950',
     'Patricia', 'Añez Saucedo', '+59172133987', 'patricia.anez@sonrisacrucena.bo', 40.00);

INSERT INTO odontologo_especialidades (odontologo_id, especialidad_id) VALUES
    ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000002'),
    ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000004'),
    ('50000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001'),
    ('50000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000005'),
    ('50000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000003');

INSERT INTO pacientes (id, ci_nit, nombres, apellidos, fecha_nacimiento, genero, telefono_whatsapp, email, direccion) VALUES
    ('60000000-0000-0000-0000-000000000001', '8945123 SC', 'Carlos Andrés', 'Justiniano Moreno', '1995-04-12', 'MASCULINO',
     '+59170012345', 'carlos.justiniano@gmail.com', 'Barrio Las Palmas, calle Los Tajibos #34'),
    ('60000000-0000-0000-0000-000000000002', '9312874 SC', 'María Fernanda', 'Vaca Díez', '1988-11-03', 'FEMENINO',
     '+59177345566', 'mfvaca@hotmail.com', 'Av. Banzer 6to anillo, Condominio Los Mangales'),
    ('60000000-0000-0000-0000-000000000003', '7654012 SC', 'José Miguel', 'Ribera Suárez', '1972-02-27', 'MASCULINO',
     '+59171239087', NULL, 'Plan 3000, UV 156, manzano 12'),
    ('60000000-0000-0000-0000-000000000004', '1028374015', 'Importadora El Trompillo', 'S.R.L.', NULL, NULL,
     '+59133556677', 'facturacion@eltrompillo.bo', 'Av. Cristo Redentor #2450'),
    ('60000000-0000-0000-0000-000000000005', '10234567 SC', 'Lucía', 'Céspedes Rojas', '2010-07-19', 'FEMENINO',
     '+59169012234', 'lucia.cespedes.madre@gmail.com', 'Urbanización Sevilla, calle 3 #8');

INSERT INTO servicios_odontologicos (id, especialidad_id, codigo, nombre, descripcion, precio_base_bs) VALUES
    ('70000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'ODG-CONS', 'Consulta y diagnóstico', 'Evaluación clínica inicial', 100.00),
    ('70000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', 'ODG-PROF', 'Profilaxis y destartraje', 'Limpieza con ultrasonido y pulido', 200.00),
    ('70000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000001', 'ODG-RES', 'Restauración con resina', 'Curación con resina fotocurable por pieza', 250.00),
    ('70000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000003', 'END-UNI', 'Endodoncia unirradicular', 'Tratamiento de conducto en pieza de una raíz', 800.00),
    ('70000000-0000-0000-0000-000000000005', '40000000-0000-0000-0000-000000000003', 'END-MOL', 'Endodoncia molar', 'Tratamiento de conductos en molar', 1200.00),
    ('70000000-0000-0000-0000-000000000006', '40000000-0000-0000-0000-000000000002', 'ORT-BRK', 'Instalación de brackets metálicos', 'Aparatología fija superior e inferior', 4500.00),
    ('70000000-0000-0000-0000-000000000007', '40000000-0000-0000-0000-000000000002', 'ORT-CTRL', 'Control mensual de ortodoncia', 'Ajuste y cambio de ligaduras', 250.00),
    ('70000000-0000-0000-0000-000000000008', '40000000-0000-0000-0000-000000000004', 'EST-BLAN', 'Blanqueamiento dental LED', 'Sesión de blanqueamiento en consultorio', 1500.00),
    ('70000000-0000-0000-0000-000000000009', '40000000-0000-0000-0000-000000000005', 'CIR-EXT', 'Extracción simple', 'Exodoncia de pieza erupcionada', 300.00),
    ('70000000-0000-0000-0000-000000000010', '40000000-0000-0000-0000-000000000005', 'CIR-TMO', 'Extracción de tercer molar', 'Cirugía de tercer molar retenido', 1100.00);

-- -------------------------------------------------------------------------
-- Sample finance data: one credit order (3 installments) and one cash order
-- -------------------------------------------------------------------------
INSERT INTO ordenes_cobro (id, paciente_id, odontologo_id, modalidad_pago, monto_subtotal_bs, descuento_bs, monto_total_bs,
                           saldo_pendiente_bs, cantidad_cuotas, estado, observaciones) VALUES
    ('80000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001',
     'CREDITO', 4500.00, 300.00, 4200.00, 4200.00, 3, 'PENDIENTE', 'Plan de brackets en 3 cuotas mensuales'),
    ('80000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000002',
     'CONTADO', 450.00, 0.00, 450.00, 450.00, 1, 'PENDIENTE', NULL);

INSERT INTO orden_cobro_items (id, orden_cobro_id, servicio_id, pieza_dental, cantidad, precio_unitario_bs, subtotal_bs) VALUES
    ('81000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000006', NULL, 1, 4500.00, 4500.00),
    ('81000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000002', '70000000-0000-0000-0000-000000000002', NULL, 1, 200.00, 200.00),
    ('81000000-0000-0000-0000-000000000003', '80000000-0000-0000-0000-000000000002', '70000000-0000-0000-0000-000000000003', '36', 1, 250.00, 250.00);

INSERT INTO cuotas_pago (id, orden_cobro_id, numero_cuota, monto_cuota_bs, fecha_vencimiento, estado) VALUES
    ('82000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001', 1, 1400.00, CURRENT_DATE, 'PENDIENTE'),
    ('82000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000001', 2, 1400.00, CURRENT_DATE + 30, 'PENDIENTE'),
    ('82000000-0000-0000-0000-000000000003', '80000000-0000-0000-0000-000000000001', 3, 1400.00, CURRENT_DATE + 60, 'PENDIENTE'),
    ('82000000-0000-0000-0000-000000000004', '80000000-0000-0000-0000-000000000002', 1, 450.00, CURRENT_DATE, 'PENDIENTE');
