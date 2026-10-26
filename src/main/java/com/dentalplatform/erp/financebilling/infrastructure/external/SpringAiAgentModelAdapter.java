package com.dentalplatform.erp.financebilling.infrastructure.external;

import com.dentalplatform.erp.financebilling.application.port.AgentModelPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.access.AccessDeniedException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Spring AI tool calling against the configured provider. Messages are literal (never prompt templates) and tool
 * callbacks are the security-proxied ERP tools, run synchronously on the caller's thread so they see its
 * authentication; a tool the caller's role forbids fails and the model only receives the failure text.
 */
@Component
class SpringAiAgentModelAdapter implements AgentModelPort {
    private final ChatClient client;
    private final List<ToolCallback> tools;

    SpringAiAgentModelAdapter(ChatClient.Builder builder, ToolCallbackProvider erpToolCallbackProvider) {
        this.client = builder.build();
        this.tools = Arrays.asList(erpToolCallbackProvider.getToolCallbacks());
    }

    @Override
    public String responder(String system, String user, Set<String> allowedTools) {
        var request = client.prompt().messages(List.of(new SystemMessage(system), new UserMessage(user)));
        var denial = new AtomicReference<AccessDeniedException>();
        List<ToolCallback> allowed = tools.stream().filter(t -> allowedTools.contains(t.getToolDefinition().name()))
                .map(t -> preserveDenial(t, denial)).toList();
        if (!allowed.isEmpty()) { request = request.toolCallbacks(allowed); }
        String answer = request.call().content();
        // Spring AI may turn a tool exception into model-readable text. A model answer cannot clear a denied action.
        if (denial.get() != null) { throw denial.get(); }
        return answer;
    }

    private ToolCallback preserveDenial(ToolCallback delegate, AtomicReference<AccessDeniedException> denial) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public String call(String input) { return invoke(input, null); }
            @Override public String call(String input, ToolContext context) { return invoke(input, context); }

            private String invoke(String input, ToolContext context) {
                try { return delegate.call(input, context); }
                catch (RuntimeException failure) {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof AccessDeniedException denied) { denial.compareAndSet(null, denied); break; }
                    }
                    throw failure;
                }
            }
        };
    }
}
