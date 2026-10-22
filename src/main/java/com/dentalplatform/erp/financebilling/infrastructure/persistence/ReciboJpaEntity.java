package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.shared.domain.vo.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recibos")
public class ReciboJpaEntity {
    @Id UUID id;
    @Column(name = "movimiento_caja_id", unique = true, nullable = false) UUID movimientoCajaId;
    @Column(name = "numero_recibo", unique = true, length = 40, nullable = false) String numero;
    @Column(name = "ci_nit_cliente", length = 30, nullable = false) String ciNit;
    @Column(name = "razon_social_cliente", length = 150, nullable = false) String razonSocial;
    @Column(nullable = false) String concepto;
    @Column(name = "monto_total_bs", precision = 12, scale = 2, nullable = false) BigDecimal monto;
    @Column(name = "pdf_url_s3") String pdfUrl;
    @Column(name = "enviado_whatsapp") boolean enviadoWhatsapp;
    @Column(name = "fecha_emision") OffsetDateTime fecha;

    protected ReciboJpaEntity() { }

    static ReciboJpaEntity fromDomain(Recibo r) {
        var e = new ReciboJpaEntity();
        e.id = r.id();
        e.movimientoCajaId = r.movimientoCajaId();
        e.numero = r.numeroRecibo();
        e.ciNit = r.ciNitCliente();
        e.razonSocial = r.razonSocialCliente();
        e.concepto = r.concepto();
        e.monto = r.montoTotal().amount();
        e.pdfUrl = r.pdfUrlS3();
        e.enviadoWhatsapp = r.enviadoWhatsapp();
        e.fecha = r.fechaEmision();
        return e;
    }

    Recibo toDomain() {
        return new Recibo(id, movimientoCajaId, numero, ciNit, razonSocial, concepto, Money.of(monto), pdfUrl,
                enviadoWhatsapp, fecha);
    }
}
