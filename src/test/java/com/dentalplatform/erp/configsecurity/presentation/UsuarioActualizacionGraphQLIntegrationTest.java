package com.dentalplatform.erp.configsecurity.presentation;

import com.dentalplatform.erp.configsecurity.application.command.ActualizarUsuarioCommand;
import com.dentalplatform.erp.configsecurity.application.usecase.ActualizarUsuarioUseCase;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * S9: actualizarUsuario. The seeded {@code admin} user (id 20000000-...-001) is the acting principal.
 */
@WithMockUser(username = "admin", roles = "ADMIN")
class UsuarioActualizacionGraphQLIntegrationTest extends IntegrationTestBase {

    private static final UUID SEEDED_ADMIN = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private static final String CREAR_USUARIO = """
            mutation($input: CrearUsuarioInput!) { crearUsuario(input: $input) { id } }""";
    private static final String ACTUALIZAR_USUARIO = """
            mutation($id: UUID!, $input: ActualizarUsuarioInput!) {
              actualizarUsuario(id: $id, input: $input) {
                id username email nombreCompleto activo rol { codigo }
              }
            }""";

    @Autowired
    private ActualizarUsuarioUseCase actualizarUsuario;

    @Autowired
    private PlatformTransactionManager transactions;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Estado(List<Map<String, Object>> fila, int audits, int outbox) { }

    private Estado estado(UUID id) {
        return new Estado(jdbc.queryForList("SELECT * FROM usuarios WHERE id = ?", id),
                jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox", Integer.class));
    }

    private int audits(String accion, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = ? AND entidad_id = ?",
                Integer.class, accion, id);
    }

    private UUID crearUsuario(String suffix, String rol) {
        return UUID.fromString(graphQl.document(CREAR_USUARIO).variable("input", Map.of("username", "user-" + suffix,
                        "email", "user-" + suffix + "@clinica.bo", "nombreCompleto", "Usuario " + suffix,
                        "password", "Secreta2026!", "rolCodigo", rol)).execute()
                .path("crearUsuario.id").entity(String.class).get());
    }

    private static Map<String, Object> input(String rol, boolean activo) {
        return new HashMap<>(Map.of("rolCodigo", rol, "activo", activo));
    }

    private void rejected(UUID id, Map<String, Object> input, ErrorType type, String fragment) {
        Estado antes = estado(id);
        GraphQlTester.Request<?> request = graphQl.document(ACTUALIZAR_USUARIO).variable("id", id)
                .variable("input", input);
        request.execute().errors().expect(e -> e.getErrorType() == type
                && (fragment == null || (e.getMessage() != null && e.getMessage().contains(fragment)))).verify();
        assertThat(estado(id)).isEqualTo(antes);
        verifyNoInteractions(rabbitTemplate);
    }

    /** Runs the body with the given users as the only active administrators, restoring the others afterwards. */
    private void withOnlyAdmins(List<UUID> keep, Runnable body) {
        List<UUID> others = jdbc.queryForList("""
                SELECT u.id FROM usuarios u JOIN roles r ON r.id = u.rol_id
                WHERE r.codigo = 'ADMIN' AND u.activo""", UUID.class).stream().filter(i -> !keep.contains(i)).toList();
        others.forEach(i -> jdbc.update("UPDATE usuarios SET activo = false WHERE id = ?", i));
        try {
            body.run();
        } finally {
            others.forEach(i -> jdbc.update("UPDATE usuarios SET activo = true WHERE id = ?", i));
        }
    }

    @Test
    void updateUserChangesRoleNameEmailAndActiveAuditsAndPublishesNoCatalogEvent() {
        String suffix = unique();
        UUID id = crearUsuario(suffix, "CAJERO");
        int outbox = jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox", Integer.class);
        Map<String, Object> cambios = input("RECEPCIONISTA", false);
        cambios.put("nombreCompleto", "Nombre Nuevo " + suffix);
        cambios.put("email", "NUEVO-" + suffix + "@clinica.bo");

        graphQl.document(ACTUALIZAR_USUARIO).variable("id", id).variable("input", cambios).execute()
                .path("actualizarUsuario.id").entity(String.class).isEqualTo(id.toString())
                .path("actualizarUsuario.username").entity(String.class).isEqualTo("user-" + suffix)
                .path("actualizarUsuario.rol.codigo").entity(String.class).isEqualTo("RECEPCIONISTA")
                .path("actualizarUsuario.activo").entity(Boolean.class).isEqualTo(false)
                .path("actualizarUsuario.nombreCompleto").entity(String.class).isEqualTo("Nombre Nuevo " + suffix)
                .path("actualizarUsuario.email").entity(String.class).isEqualTo("nuevo-" + suffix + "@clinica.bo");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM usuarios WHERE id = ?", id);
        assertThat(row).containsEntry("activo", false).containsEntry("username", "user-" + suffix);
        assertThat(row.get("password_hash")).isNotNull();
        assertThat(audits("USUARIO_DESACTIVADO", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox", Integer.class)).isEqualTo(outbox);

        // reactivation and an update that does not touch name/email
        graphQl.document(ACTUALIZAR_USUARIO).variable("id", id).variable("input", input("CAJERO", true)).execute()
                .path("actualizarUsuario.activo").entity(Boolean.class).isEqualTo(true)
                .path("actualizarUsuario.nombreCompleto").entity(String.class).isEqualTo("Nombre Nuevo " + suffix);
        assertThat(audits("USUARIO_ACTUALIZADO", id)).isEqualTo(1);
    }

    @Test
    void updateUserWithAnotherUsersEmailIsBadRequestAndKeepingItsOwnIsAllowed() {
        String otro = unique();
        crearUsuario(otro, "CAJERO");
        String suffix = unique();
        UUID id = crearUsuario(suffix, "CAJERO");

        Map<String, Object> duplicado = input("CAJERO", true);
        duplicado.put("email", "USER-" + otro + "@clinica.bo");
        rejected(id, duplicado, ErrorType.BAD_REQUEST, "user-" + otro + "@clinica.bo");

        Map<String, Object> propio = input("CAJERO", true);
        propio.put("email", "user-" + suffix + "@clinica.bo");
        graphQl.document(ACTUALIZAR_USUARIO).variable("id", id).variable("input", propio).execute()
                .path("actualizarUsuario.email").entity(String.class).isEqualTo("user-" + suffix + "@clinica.bo");
    }

    @Test
    void updateUserWithUnknownRoleOrInvalidEmailIsBadRequestAndChangesNothing() {
        UUID id = crearUsuario(unique(), "CAJERO");

        rejected(id, input("SUPERUSUARIO", true), ErrorType.BAD_REQUEST, "No existe el rol SUPERUSUARIO");
        Map<String, Object> malEmail = input("CAJERO", true);
        malEmail.put("email", "sin-arroba");
        rejected(id, malEmail, ErrorType.BAD_REQUEST, "El email no es válido");
    }

    @Test
    void updateUnknownUserIsNotFound() {
        UUID id = UUID.randomUUID();
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);

        graphQl.document(ACTUALIZAR_USUARIO).variable("id", id).variable("input", input("CAJERO", true)).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.NOT_FOUND).verify();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
    }

    @Test
    @WithMockUser(username = "recepcion", roles = "RECEPCIONISTA")
    void onlyAdminsCanUpdateUsers() {
        rejected(SEEDED_ADMIN, input("ADMIN", true), ErrorType.FORBIDDEN, null);
    }

    @Test
    void nobodyCanDeactivateThemselvesNorRemoveTheirOwnAdminRole() {
        rejected(SEEDED_ADMIN, input("ADMIN", false), ErrorType.BAD_REQUEST, "no puede desactivarse a sí mismo");
        rejected(SEEDED_ADMIN, input("CAJERO", true), ErrorType.BAD_REQUEST,
                "no puede quitarse a sí mismo el rol ADMIN");
        assertThat(jdbc.queryForObject("SELECT activo FROM usuarios WHERE id = ?", Boolean.class, SEEDED_ADMIN))
                .isTrue();
    }

    @Test
    void theLastActiveAdminCannotBeDeactivatedOrDemotedButCanOnceAnotherAdminExists() {
        UUID unico = crearUsuario(unique(), "ADMIN");
        withOnlyAdmins(List.of(unico), () -> {
            rejected(unico, input("ADMIN", false), ErrorType.BAD_REQUEST, "al menos un administrador activo");
            rejected(unico, input("CAJERO", true), ErrorType.BAD_REQUEST, "al menos un administrador activo");

            UUID segundo = crearUsuario(unique(), "ADMIN");
            graphQl.document(ACTUALIZAR_USUARIO).variable("id", unico).variable("input", input("ADMIN", false))
                    .execute().path("actualizarUsuario.activo").entity(Boolean.class).isEqualTo(false);
            assertThat(jdbc.queryForObject("SELECT activo FROM usuarios WHERE id = ?", Boolean.class, segundo))
                    .isTrue();
        });
    }

    @Test
    void twoConcurrentDeactivationsOfTheLastTwoAdminsLetExactlyOneSucceed() throws Exception {
        UUID a = crearUsuario(unique(), "ADMIN");
        UUID b = crearUsuario(unique(), "ADMIN");
        withOnlyAdmins(List.of(a, b), () -> {
            CountDownLatch firstInsideTransaction = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            TransactionTemplate tx = new TransactionTemplate(transactions);

            // T1 deactivates A and keeps its transaction (and the admin row locks) open
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                actualizarUsuario.ejecutar(new ActualizarUsuarioCommand(a, "ADMIN", false, null, null));
                firstInsideTransaction.countDown();
                await(releaseFirst);
            }));
            await(firstInsideTransaction);
            // T2 deactivates B concurrently: it must wait for T1 instead of reading a stale admin count
            CompletableFuture<Void> second = CompletableFuture.runAsync(() ->
                    actualizarUsuario.ejecutar(new ActualizarUsuarioCommand(b, "ADMIN", false, null, null)));

            assertThatThrownBy(() -> second.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();

            first.join();
            assertThatThrownBy(second::get).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("al menos un administrador activo");
            assertThat(jdbc.queryForObject("SELECT activo FROM usuarios WHERE id = ?", Boolean.class, a)).isFalse();
            assertThat(jdbc.queryForObject("SELECT activo FROM usuarios WHERE id = ?", Boolean.class, b)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM usuarios u JOIN roles r ON r.id = u.rol_id
                    WHERE r.codigo = 'ADMIN' AND u.activo""", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void unsynchronizedRaceOfTheLastTwoAdminsAlsoLeavesExactlyOneActive() {
        UUID a = crearUsuario(unique(), "ADMIN");
        UUID b = crearUsuario(unique(), "ADMIN");
        withOnlyAdmins(List.of(a, b), () -> {
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<Boolean> t1 = CompletableFuture.supplyAsync(() -> deactivate(a, go));
            CompletableFuture<Boolean> t2 = CompletableFuture.supplyAsync(() -> deactivate(b, go));
            go.countDown();

            assertThat(List.of(t1.join(), t2.join())).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM usuarios u JOIN roles r ON r.id = u.rol_id
                    WHERE r.codigo = 'ADMIN' AND u.activo""", Integer.class)).isEqualTo(1);
        });
    }

    private boolean deactivate(UUID id, CountDownLatch go) {
        await(go);
        try {
            actualizarUsuario.ejecutar(new ActualizarUsuarioCommand(id, "ADMIN", false, null, null));
            return true;
        } catch (BusinessRuleException e) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
