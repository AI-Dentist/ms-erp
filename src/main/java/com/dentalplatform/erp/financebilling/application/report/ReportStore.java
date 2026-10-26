package com.dentalplatform.erp.financebilling.application.report;

import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/** Process-local FIFO, 64 reports total, ten-minute absolute TTL. Reads never extend the TTL. */
@Component
public class ReportStore {
    private final Clock clock;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();

    public ReportStore(Clock clock) { this.clock = clock; }

    public synchronized void put(String owner, ReportResponse response, ReportResult result) {
        purge();
        if (entries.size() >= 64) { entries.remove(entries.keySet().iterator().next()); }
        Instant now = clock.instant();
        entries.put(response.reporteId(), new Entry(owner, now.plusSeconds(600), now, response, result));
    }

    public synchronized Optional<Entry> find(String owner, UUID id) {
        purge();
        Entry entry = entries.get(id);
        return entry != null && entry.owner().equals(owner) ? Optional.of(entry) : Optional.empty();
    }

    private void purge() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
    }

    public record Entry(String owner, Instant expiresAt, Instant createdAt, ReportResponse response,
                        ReportResult result) { }
}
