package com.dentalplatform.erp.financebilling.application.port;

import java.util.Set;

/** The real chat model (OpenAI or an OpenAI-compatible provider such as Gemini) with ERP tool calling. */
public interface AgentModelPort {
    /**
     * @param system       trusted rules of the agent
     * @param user         untrusted user text (or data to summarize)
     * @param allowedTools names of the tools the model may call; empty means no tools at all
     * @return the model's final answer after any tool calls (executed with the caller's security context)
     */
    String responder(String system, String user, Set<String> allowedTools);
}
