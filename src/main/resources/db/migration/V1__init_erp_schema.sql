-- V1: initial ERP schema (source: SPECS.md section 2)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Control de Idempotencia para evitar cobros o registros duplicados en redes inestables
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    operation_name VARCHAR(100) NOT NULL,
    response_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- =========================================================================
-- CONTEXTO 1: CONFIGURACIÓN, SEGURIDAD Y AUDITORÍA
-- =========================================================================

CREATE TABLE empresa_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre_comercial VARCHAR(150) NOT NULL,
    razon_social VARCHAR(150) NOT NULL,
    nit VARCHAR(30) NOT NULL,
    direccion TEXT NOT NULL,
    telefono_contacto VARCHAR(30) NOT NULL,
    email_contacto VARCHAR(100),
    logo_url TEXT,
    moneda_simbolo VARCHAR(10) DEFAULT 'Bs',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(50) UNIQUE NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    descripcion TEXT
);

CREATE TABLE permisos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(80) UNIQUE NOT NULL,
    modulo VARCHAR(50) NOT NULL,
    descripcion TEXT
);

CREATE TABLE rol_permisos (
    rol_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permiso_id UUID NOT NULL REFERENCES permisos(id) ON DELETE CASCADE,
    PRIMARY KEY (rol_id, permiso_id)
);

CREATE TABLE usuarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id VARCHAR(100) UNIQUE,
    rol_id UUID NOT NULL REFERENCES roles(id),
    username VARCHAR(80) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    email VARCHAR(120) UNIQUE NOT NULL,
    nombre_completo VARCHAR(150) NOT NULL,
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID REFERENCES usuarios(id),
    accion VARCHAR(100) NOT NULL,
    entidad_afectada VARCHAR(80) NOT NULL,
    entidad_id UUID,
    detalle_json JSONB,
    ip_origen VARCHAR(45),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================================
-- CONTEXTO 2: CATÁLOGOS MAESTROS
-- =========================================================================

CREATE TABLE consultorios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(20) UNIQUE NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    ubicacion VARCHAR(150),
    estado VARCHAR(30) DEFAULT 'DISPONIBLE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE especialidades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(30) UNIQUE NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    descripcion TEXT,
    activo BOOLEAN DEFAULT TRUE
);

CREATE TABLE odontologos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID UNIQUE REFERENCES usuarios(id) ON DELETE SET NULL,
    ci VARCHAR(25) UNIQUE NOT NULL,
    matricula_profesional VARCHAR(50) UNIQUE NOT NULL,
    nombres VARCHAR(100) NOT NULL,
    apellidos VARCHAR(100) NOT NULL,
    telefono VARCHAR(30) NOT NULL,
    email VARCHAR(120),
    porcentaje_comision NUMERIC(5,2) DEFAULT 0.00,
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE odontologo_especialidades (
    odontologo_id UUID NOT NULL REFERENCES odontologos(id) ON DELETE CASCADE,
    especialidad_id UUID NOT NULL REFERENCES especialidades(id) ON DELETE CASCADE,
    PRIMARY KEY (odontologo_id, especialidad_id)
);

CREATE TABLE pacientes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ci_nit VARCHAR(30) UNIQUE NOT NULL,
    nombres VARCHAR(100) NOT NULL,
    apellidos VARCHAR(100) NOT NULL,
    fecha_nacimiento DATE,
    genero VARCHAR(20),
    telefono_whatsapp VARCHAR(30) NOT NULL,
    email VARCHAR(120),
    direccion TEXT,
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE servicios_odontologicos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    especialidad_id UUID REFERENCES especialidades(id),
    codigo VARCHAR(30) UNIQUE NOT NULL,
    nombre VARCHAR(150) NOT NULL,
    descripcion TEXT,
    precio_base_bs NUMERIC(12,2) NOT NULL CHECK (precio_base_bs >= 0),
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================================
-- CONTEXTO 3: FINANZAS, PLANES DE PAGO, PAGOFÁCIL QR Y CAJA
-- =========================================================================

CREATE TABLE ordenes_cobro (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paciente_id UUID NOT NULL REFERENCES pacientes(id),
    odontologo_id UUID REFERENCES odontologos(id),
    consulta_externa_id UUID UNIQUE,
    modalidad_pago VARCHAR(20) NOT NULL DEFAULT 'CONTADO',
    monto_subtotal_bs NUMERIC(12,2) NOT NULL,
    descuento_bs NUMERIC(12,2) DEFAULT 0.00,
    monto_total_bs NUMERIC(12,2) NOT NULL,
    saldo_pendiente_bs NUMERIC(12,2) NOT NULL,
    cantidad_cuotas INT NOT NULL DEFAULT 1 CHECK (cantidad_cuotas >= 1),
    estado VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE',
    observaciones TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orden_cobro_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    orden_cobro_id UUID NOT NULL REFERENCES ordenes_cobro(id) ON DELETE CASCADE,
    servicio_id UUID NOT NULL REFERENCES servicios_odontologicos(id),
    pieza_dental VARCHAR(10),
    cantidad INT NOT NULL DEFAULT 1,
    precio_unitario_bs NUMERIC(12,2) NOT NULL,
    subtotal_bs NUMERIC(12,2) NOT NULL
);

CREATE TABLE cuotas_pago (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    orden_cobro_id UUID NOT NULL REFERENCES ordenes_cobro(id) ON DELETE CASCADE,
    numero_cuota INT NOT NULL,
    monto_cuota_bs NUMERIC(12,2) NOT NULL,
    fecha_vencimiento DATE NOT NULL,
    estado VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE',
    fecha_pago TIMESTAMP WITH TIME ZONE,
    UNIQUE (orden_cobro_id, numero_cuota)
);

CREATE TABLE transacciones_qr (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cuota_id UUID NOT NULL REFERENCES cuotas_pago(id),
    pagofacil_transaction_id VARCHAR(100) UNIQUE NOT NULL,
    qr_imagen_base64 TEXT NOT NULL,
    monto_bs NUMERIC(12,2) NOT NULL,
    moneda VARCHAR(10) DEFAULT 'BOB',
    estado VARCHAR(30) NOT NULL DEFAULT 'GENERADO',
    raw_webhook_payload JSONB,
    fecha_creacion TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    fecha_expiracion TIMESTAMP WITH TIME ZONE,
    fecha_confirmacion TIMESTAMP WITH TIME ZONE
);

CREATE TABLE movimientos_caja (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cuota_id UUID UNIQUE REFERENCES cuotas_pago(id),
    usuario_cajero_id UUID NOT NULL REFERENCES usuarios(id),
    tipo_movimiento VARCHAR(20) NOT NULL,
    categoria VARCHAR(50) NOT NULL,
    metodo_pago VARCHAR(30) NOT NULL,
    monto_bs NUMERIC(12,2) NOT NULL CHECK (monto_bs > 0),
    descripcion TEXT NOT NULL,
    fecha_movimiento TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE recibos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    movimiento_caja_id UUID UNIQUE NOT NULL REFERENCES movimientos_caja(id),
    numero_recibo VARCHAR(40) UNIQUE NOT NULL,
    ci_nit_cliente VARCHAR(30) NOT NULL,
    razon_social_cliente VARCHAR(150) NOT NULL,
    concepto TEXT NOT NULL,
    monto_total_bs NUMERIC(12,2) NOT NULL,
    pdf_url_s3 TEXT,
    enviado_whatsapp BOOLEAN DEFAULT FALSE,
    fecha_emision TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pacientes_ci_nit ON pacientes(ci_nit);
CREATE INDEX idx_ordenes_paciente ON ordenes_cobro(paciente_id);
CREATE INDEX idx_ordenes_estado ON ordenes_cobro(estado);
CREATE INDEX idx_cuotas_vencimiento ON cuotas_pago(fecha_vencimiento, estado);
CREATE INDEX idx_movimientos_fecha_tipo ON movimientos_caja(fecha_movimiento, tipo_movimiento);
