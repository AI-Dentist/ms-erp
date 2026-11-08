package com.dentalplatform.erp.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Starts one real PostgreSQL 18 server per JVM for tests (no Docker required).
 */
public final class EmbeddedPostgresSupport {

    private static EmbeddedPostgres instance;

    private EmbeddedPostgresSupport() {
    }

    public static synchronized EmbeddedPostgres postgres() {
        if (instance == null) {
            try {
                // Spring caches one context (and Hikari pool) per distinct test configuration;
                // the default 100 connections is exhausted by the full suite.
                instance = EmbeddedPostgres.builder()
                        .setServerConfig("max_connections", "400")
                        .start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        instance.close();
                    } catch (IOException ignored) {
                        // JVM is exiting
                    }
                }));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
        }
        return instance;
    }

    public static String jdbcUrl(String database) {
        return postgres().getJdbcUrl("postgres", database);
    }
}
