package com.dentalplatform.erp.financebilling.application.command;

import java.util.UUID;

public record CobrarEfectivoCommand(UUID cuotaId, UUID usuarioCajeroId, String ciNitCliente, String razonSocialCliente) { }
