package com.dentalplatform.erp.financebilling.application.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the tools actually executed during one agent run. A tool records itself only after it succeeded, so a tool the
 * model asked for but the caller's role denied (or whose use case failed) never appears. Bound to the thread that
 * runs the agent (Spring AI executes blocking tool calls on the caller's thread); when no run is active, e.g. a direct
 * MCP tool call, recording is a no-op.
 */
@Component
public class AgentToolTrace {
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    public Run begin() {
        Run run = new Run();
        CURRENT.set(run);
        return run;
    }

    public void end() { CURRENT.remove(); }

    void executed(String tool) {
        Run run = CURRENT.get();
        if (run != null) { run.tools.add(tool); }
    }

    void crud(String tool, UUID affectedId) {
        Run run = CURRENT.get();
        if (run != null) { run.crud = tool + ":" + affectedId; }
    }

    void report(UUID reportId) {
        Run run = CURRENT.get();
        if (run != null) { run.reportId = reportId; }
    }

    void n8nSent(boolean sent) {
        Run run = CURRENT.get();
        if (run != null) { run.n8nSent |= sent; }
    }

    public static final class Run {
        private final List<String> tools = new ArrayList<>();
        private String crud;
        private boolean n8nSent;
        private UUID reportId;

        public List<String> tools() { return List.copyOf(tools); }
        public String crud() { return crud; }
        public boolean n8nSent() { return n8nSent; }
        public UUID reportId() { return reportId; }
    }
}
