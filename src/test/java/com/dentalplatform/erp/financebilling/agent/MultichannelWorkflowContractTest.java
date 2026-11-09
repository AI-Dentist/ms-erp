package com.dentalplatform.erp.financebilling.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MultichannelWorkflowContractTest {
    @Test void importableWorkflowHasAuthenticatedGatedBranchesAndOneResponseAfterTheJoin() throws Exception {
        var root = JsonMapper.builder().build().readTree(Files.readString(Path.of("infrastructure/n8n/n8n-workflow-multicanal.json")));
        Map<String,JsonNode> nodes = new HashMap<>();
        root.path("nodes").forEach(n -> assertThat(nodes.put(n.path("name").asString(), n)).isNull());
        assertThat(root.path("active").asBoolean()).isFalse();
        assertThat(root.path("settings").path("executionOrder").asString()).isEqualTo("v1");
        assertThat(root.path("settings").path("saveDataSuccessExecution").asString()).isEqualTo("none");
        assertThat(root.path("settings").path("saveDataErrorExecution").asString()).isEqualTo("none");
        var webhook = nodes.get("ERP notifications");
        assertThat(webhook.path("type").asString()).isEqualTo("n8n-nodes-base.webhook");
        assertThat(webhook.path("parameters").path("httpMethod").asString()).isEqualTo("POST");
        assertThat(webhook.path("parameters").path("path").asString()).isEqualTo("erp-notificaciones");
        assertThat(webhook.path("parameters").path("responseMode").asString()).isEqualTo("responseNode");
        assertThat(webhook.path("parameters").path("authentication").asString()).isEqualTo("headerAuth");
        assertThat(nodes.get("WhatsApp Meta").path("parameters").path("url").asString()).isEqualTo("=https://graph.facebook.com/v20.0/{{$env.META_PHONE_NUMBER_ID}}/messages");
        assertThat(nodes.get("Telegram").path("type").asString()).isEqualTo("n8n-nodes-base.telegram");
        assertThat(nodes.get("Telegram").path("parameters").path("operation").asString()).isEqualTo("sendMessage");
        assertThat(nodes.get("Telegram").toString()).contains("CHAT_ID", "Markdown");
        assertThat(nodes.get("Gmail SMTP").path("parameters").path("subject").asString()).isEqualTo("Aviso de Cobranza - Clínica Dental");
        assertThat(nodes.get("Gmail SMTP").path("parameters").path("emailFormat").asString()).isEqualTo("html");
        for (String channel : List.of("WHATSAPP", "TELEGRAM", "GMAIL")) {
            assertThat(nodes.get("Enable " + channel).path("type").asString()).isEqualTo("n8n-nodes-base.if");
            assertThat(nodes.get("Enable " + channel).toString()).contains("canalesActivos", channel);
        }
        assertThat(nodes.get("Respond").path("type").asString()).isEqualTo("n8n-nodes-base.respondToWebhook");
        assertThat(nodes.get("Respond").path("parameters").path("options").path("responseCode").asString()).contains("200");
        root.path("connections").properties().forEach(entry -> {
            assertThat(nodes).containsKey(entry.getKey());
            entry.getValue().path("main").forEach(branch -> branch.forEach(edge -> assertThat(nodes).containsKey(edge.path("node").asString())));
        });
        assertThat(root.path("connections").path("Join all channels").toString()).contains("Confirm channels");
        assertThat(root.path("connections").path("Confirm channels").toString()).contains("Respond");
        assertThat(root.path("pinData").size()).isZero();
    }
}
