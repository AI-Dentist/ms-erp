package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.TransaccionQr;
import com.dentalplatform.erp.shared.domain.vo.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transacciones_qr")
public class TransaccionQrJpaEntity {
    @Id UUID id;
    @Column(name = "cuota_id", nullable = false) UUID cuotaId;
    @Column(name = "pagofacil_transaction_id", nullable = false, unique = true, length = 100) String reference;
    @Column(name = "company_transaction_id", unique = true, length = 100) String companyReference;
    @Column(name = "qr_imagen_base64", nullable = false, columnDefinition = "text") String image;
    @Column(name = "monto_bs", nullable = false, precision = 12, scale = 2) BigDecimal amount;
    @Column(length = 10) String moneda;
    @Column(length = 30) String estado;
    @Column(name = "fecha_creacion") OffsetDateTime created;
    @Column(name = "fecha_expiracion") OffsetDateTime expires;
    @Column(name = "fecha_confirmacion") OffsetDateTime confirmed;
    @Column(name = "ci_nit_cliente", length = 30) String ciNit;
    @Column(name = "razon_social_cliente", length = 150) String name;
    @Column(name = "usuario_cajero_id") UUID cashier;

    protected TransaccionQrJpaEntity() { }

    static TransaccionQrJpaEntity fromDomain(TransaccionQr t) {
        var e = new TransaccionQrJpaEntity();
        e.id = t.id();
        e.cuotaId = t.cuotaId();
        e.reference = t.pagofacilTransactionId();
        e.companyReference = t.companyTransactionId();
        e.image = t.qrImagenBase64();
        e.amount = t.monto().amount();
        e.moneda = "BOB";
        e.estado = t.estado();
        e.created = t.fechaCreacion();
        e.expires = t.fechaExpiracion();
        e.confirmed = t.fechaConfirmacion();
        e.ciNit = t.ciNitCliente();
        e.name = t.razonSocialCliente();
        e.cashier = t.usuarioCajeroId();
        return e;
    }

    TransaccionQr toDomain() {
        return new TransaccionQr(id, cuotaId, reference, image, Money.of(amount), estado, created, expires,
                confirmed, ciNit, name, cashier, companyReference);
    }
}
