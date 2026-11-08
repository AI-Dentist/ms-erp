package com.dentalplatform.erp.configsecurity.presentation;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Public GraphQL observations only; synthetic JWT contexts, database and provider substitutes. */
@WithMockUser(username = "admin", roles = "ADMIN")
class UsuarioProvisioningGraphQLIntegrationTest extends IntegrationTestBase {
    private static final String CREATE = """
            mutation($input: CrearUsuarioConIdentidadInput!) {
              crearUsuarioConIdentidad(input: $input) { id username email nombreCompleto activo rol { codigo } }
            }""";
    private static final String LEGACY = "mutation($i: CrearUsuarioInput!) { crearUsuario(input: $i) { id } }";
    private static final String CONFLICT = "La identidad de usuario ya está vinculada";
    @MockitoSpyBean RegistrarAuditoriaUseCase auditoria;

    private record Request(Map<String, Object> user, String subject, String doctor) {
        Map<String, Object> input() {
            var input = new HashMap<String, Object>();
            input.put("usuario", user);
            input.put("keycloakSubject", subject);
            if (doctor != null) input.put("odontologoId", doctor);
            return input;
        }
        String action() { return doctor == null ? "IDENTIDAD_USUARIO_VINCULADA" : "IDENTIDAD_ODONTOLOGO_VINCULADA"; }
    }
    private record Snapshot(List<Object> users, List<Object> doctors, List<Object> audits) { }

    private Request fixture(boolean doctor) {
        String suffix = UUID.randomUUID().toString().substring(0, 18);
        String username = "provision-" + suffix;
        String id = doctor ? graphQl.document("""
                mutation($i: CrearOdontologoInput!) { crearOdontologo(input: $i) { id } }
                """).variable("i", Map.of("ci", "P-" + suffix, "matriculaProfesional", "P-" + suffix,
                "nombres", "Provision", "apellidos", suffix, "telefono", "+59170000000",
                "porcentajeComision", 30, "especialidadesIds", List.of())).execute()
                .path("crearOdontologo.id").entity(String.class).get() : null;
        return new Request(Map.of("rolCodigo", doctor ? "ODONTOLOGO" : "CAJERO", "username", username,
                "email", username + "@example.test", "nombreCompleto", "Provision " + suffix,
                "password", "Synthetic2026!"), UUID.randomUUID().toString(), id);
    }

    private List<Object> rows(String document, String path) {
        return graphQl.document(document).execute().path(path).entityList(Object.class).get().stream()
                .sorted(java.util.Comparator.comparing(row -> ((Map<?, ?>) row).get("id").toString())).toList();
    }
    private Snapshot snapshot() {
        return new Snapshot(rows("{ listarUsuarios { id username email nombreCompleto activo rol { codigo } } }", "listarUsuarios"),
                rows("{ listarOdontologos(soloActivos:false) { id ci nombres apellidos activo } }", "listarOdontologos"),
                rows("{ listarAuditLogs(limite:500) { id accion entidadId detalleJson createdAt } }", "listarAuditLogs"));
    }
    private GraphQlTester.Response provision(Request r) { return graphQl.document(CREATE).variable("input", r.input()).execute(); }
    private String success(Request r) {
        return provision(r).path("crearUsuarioConIdentidad.username").entity(String.class)
                .isEqualTo(r.user().get("username").toString().trim().toLowerCase(Locale.ROOT))
                .path("crearUsuarioConIdentidad.email").entity(String.class)
                .isEqualTo(r.user().get("email").toString().trim().toLowerCase(Locale.ROOT))
                .path("crearUsuarioConIdentidad.nombreCompleto").entity(String.class)
                .isEqualTo(r.user().get("nombreCompleto").toString().trim())
                .path("crearUsuarioConIdentidad.activo").entity(Boolean.class).isEqualTo(true)
                .path("crearUsuarioConIdentidad.rol.codigo").entity(String.class).isEqualTo(r.user().get("rolCodigo").toString())
                .path("crearUsuarioConIdentidad.id").entity(String.class).get();
    }
    private <T> T asJwt(String subject, String username, String role, Supplier<T> action) {
        var old = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("synthetic-service-test").header("alg", "RS256").subject(subject)
                .claim("preferred_username", username).build();
        context.setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_" + role), username));
        SecurityContextHolder.setContext(context);
        try { return action.get(); } finally { SecurityContextHolder.setContext(old); }
    }
    private void error(GraphQlTester.Response response, String field, ErrorType type, String message) {
        response.errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getErrorType()).isEqualTo(type);
            if (message != null) assertThat(errors.getFirst().getMessage()).isEqualTo(message);
        });
        response.path(field).pathDoesNotExist();
    }
    private void unavailable(Request r) {
        error(asJwt(r.subject(), r.user().get("username").toString(), "ADMIN",
                () -> graphQl.document("{ obtenerMiUsuarioId }").execute()), "obtenerMiUsuarioId",
                ErrorType.NOT_FOUND, "Identidad de usuario no disponible");
        error(asJwt(r.subject(), r.user().get("username").toString(), "ADMIN",
                () -> graphQl.document("{ obtenerMiOdontologoId }").execute()), "obtenerMiOdontologoId",
                ErrorType.NOT_FOUND, "Identidad odontológica no disponible");
    }
    private void linked(Request r, String user) {
        asJwt(r.subject(), "deliberately-not-the-owner", "ADMIN", () -> graphQl.document("{ obtenerMiUsuarioId }").execute())
                .path("obtenerMiUsuarioId").entity(String.class).isEqualTo(user);
        if (r.doctor() != null) asJwt(r.subject(), "not-the-owner", "ADMIN",
                () -> graphQl.document("{ obtenerMiOdontologoId }").execute())
                .path("obtenerMiOdontologoId").entity(String.class).isEqualTo(r.doctor());
    }
    private void rejected(Request r, ErrorType type, String message) {
        Snapshot before = snapshot();
        error(provision(r), "crearUsuarioConIdentidad", type, message);
        assertThat(snapshot()).isEqualTo(before);
    }
    private List<String> actions(String user) {
        return snapshot().audits().stream().map(row -> (Map<?, ?>) row)
                .filter(row -> user.equals(row.get("entidadId")) || String.valueOf(row.get("detalleJson")).contains(user))
                .map(row -> row.get("accion").toString()).sorted().toList();
    }
    private void failAudit(Request r, AtomicBoolean fail) {
        doAnswer(call -> {
            if (fail.get() && r.action().equals(call.getArgument(0)))
                throw new TransientDataAccessResourceException("Synthetic provisioning audit outage");
            return call.callRealMethod();
        }).when(auditoria).registrar(anyString(), anyString(), any(UUID.class), anyMap());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void atomicCreateLinksIdentityAndExactNormalizedReplayDoesNotWrite(boolean doctor) {
        Request r = fixture(doctor);
        Snapshot before = snapshot();
        String user = success(r);
        assertThat(snapshot().users()).hasSize(before.users().size() + 1);
        assertThat(snapshot().doctors()).isEqualTo(before.doctors());
        assertThat(actions(user)).containsExactlyInAnyOrder("USUARIO_CREADO", r.action());
        linked(r, user);
        Snapshot linked = snapshot();
        var normalized = new HashMap<>(r.user());
        normalized.put("username", " " + r.user().get("username").toString().toUpperCase(Locale.ROOT) + " ");
        normalized.put("email", " " + r.user().get("email").toString().toUpperCase(Locale.ROOT) + " ");
        normalized.put("nombreCompleto", " " + r.user().get("nombreCompleto") + " ");
        assertThat(success(new Request(normalized, r.subject(), r.doctor()))).isEqualTo(user);
        assertThat(snapshot()).isEqualTo(linked);
        linked(r, user);
    }

    @Test void subjectOwnedByAnotherUserFailsExplicitlyWithoutCreatingUserOrChangingOriginal() {
        Request original = fixture(false);
        String user = graphQl.document(LEGACY).variable("i", original.user()).execute().path("crearUsuario.id").entity(String.class).get();
        graphQl.document("mutation($u:UUID!,$s:String!) { vincularIdentidadUsuario(input:{usuarioId:$u,keycloakSubject:$s}) }")
                .variable("u", user).variable("s", original.subject()).execute().path("vincularIdentidadUsuario").entity(String.class).isEqualTo(user);
        Request other = fixture(true);
        rejected(new Request(other.user(), original.subject(), other.doctor()), ErrorType.BAD_REQUEST, CONFLICT);
        linked(original, user);
        unavailable(other);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failureAfterLinkageRollsBackUserDoctorAndAuditsThenRetryCanUseSameDoctor(boolean doctor) {
        Request r = fixture(doctor);
        Snapshot before = snapshot();
        AtomicBoolean fail = new AtomicBoolean(true);
        failAudit(r, fail);
        try {
            error(provision(r), "crearUsuarioConIdentidad", ErrorType.INTERNAL_ERROR, null);
            assertThat(snapshot()).isEqualTo(before);
            unavailable(r);
        } finally { fail.set(false); }
        String user = success(r);
        linked(r, user);
        assertThat(actions(user)).containsExactlyInAnyOrder("USUARIO_CREADO", r.action());
        Snapshot after = snapshot();
        assertThat(success(r)).isEqualTo(user);
        assertThat(snapshot()).isEqualTo(after);
    }

    @Test void legacyIndependentCallsRemainCompatibleButCannotRollbackAnEarlierCreate() {
        Request r = fixture(false);
        String user = graphQl.document(LEGACY).variable("i", r.user()).execute().path("crearUsuario.id").entity(String.class).get();
        Snapshot created = snapshot();
        AtomicBoolean fail = new AtomicBoolean(true);
        failAudit(r, fail);
        try {
            error(graphQl.document("mutation($u:UUID!,$s:String!) { vincularIdentidadUsuario(input:{usuarioId:$u,keycloakSubject:$s}) }")
                    .variable("u", user).variable("s", r.subject()).execute(), "vincularIdentidadUsuario", ErrorType.INTERNAL_ERROR, null);
            assertThat(snapshot()).isEqualTo(created);
            assertThat(actions(user)).containsExactly("USUARIO_CREADO");
            unavailable(r); // Characterizes the existing orphan hazard, NOT an atomic provisioning API.
        } finally { fail.set(false); }
    }

    @Test void conflictingReplayCannotChangePasswordRoleProfileOrDoctor() {
        Request r = fixture(true);
        String user = success(r);
        for (var change : List.of(Map.entry("password", "DifferentSynthetic2026!"), Map.entry("rolCodigo", "ADMIN"),
                Map.entry("nombreCompleto", "Different name"), Map.entry("email", "other-" + r.user().get("email")))) {
            var changed = new HashMap<>(r.user());
            changed.put(change.getKey(), change.getValue());
            rejected(new Request(changed, r.subject(), r.doctor()), ErrorType.BAD_REQUEST, CONFLICT);
            linked(r, user);
        }
        rejected(new Request(r.user(), r.subject(), null), ErrorType.BAD_REQUEST, CONFLICT);
        rejected(new Request(r.user(), r.subject(), fixture(true).doctor()), ErrorType.BAD_REQUEST, CONFLICT);
        linked(r, user);
    }

    @Test void existingUnboundUsernameIsNotAdoptedByProvisioning() {
        Request r = fixture(false);
        graphQl.document(LEGACY).variable("i", r.user()).execute().path("crearUsuario.id").entity(String.class).get();
        rejected(r, ErrorType.BAD_REQUEST, "Ya existe un usuario con el username " + r.user().get("username"));
        unavailable(r);
    }

    @Test void missingInactiveAndAlreadyOwnedDoctorsDoNotLeaveNewUsers() {
        Request r = fixture(false);
        rejected(new Request(r.user(), r.subject(), UUID.randomUUID().toString()), ErrorType.NOT_FOUND, "Odontólogo no encontrado");
        Request inactive = fixture(true);
        graphQl.document("mutation($id:UUID!) { eliminarOdontologo(id:$id) }").variable("id", inactive.doctor()).execute()
                .path("eliminarOdontologo").entity(Boolean.class).isEqualTo(true);
        rejected(inactive, ErrorType.BAD_REQUEST, "El odontólogo está inactivo");
        unavailable(inactive);
        Request owner = fixture(true);
        String user = success(owner);
        rejected(new Request(r.user(), r.subject(), owner.doctor()), ErrorType.BAD_REQUEST, "La identidad odontológica ya está vinculada");
        unavailable(r);
        linked(owner, user);
    }

    @Test void blankAndOverlongSubjectsRejectWithoutWritesAndUnicodeBoundaryKeepsOpaqueCaseAndSpaces() {
        Request r = fixture(false);
        for (String invalid : List.of("", "   ", "X".repeat(101))) rejected(new Request(r.user(), invalid, null),
                ErrorType.BAD_REQUEST, "El subject de Keycloak debe tener entre 1 y 100 caracteres y no estar en blanco");
        String subject = " Case " + "😀".repeat(93) + " ";
        assertThat(subject.codePointCount(0, subject.length())).isEqualTo(100);
        Request opaque = new Request(r.user(), subject, null);
        linked(opaque, success(opaque));
        unavailable(new Request(r.user(), subject.trim(), null));
        unavailable(new Request(r.user(), subject.toLowerCase(Locale.ROOT), null));
    }

    @Test void nonAdminRolesCannotProvisionOrWriteAnything() {
        Request r = fixture(true);
        Snapshot before = snapshot();
        for (String role : List.of("CAJERO", "RECEPCIONISTA", "ODONTOLOGO")) {
            error(asJwt("synthetic-denied", "admin", role, () -> provision(r)), "crearUsuarioConIdentidad", ErrorType.FORBIDDEN, null);
            assertThat(snapshot()).isEqualTo(before);
        }
        unavailable(r);
    }

    @Test void concurrentIdenticalRequestsOverlapBeforeCommitAndReturnOneUserAndAuditPair() throws Exception {
        Request r = fixture(true);
        Snapshot before = snapshot();
        CountDownLatch inAudit = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (r.action().equals(call.getArgument(0)) && first.compareAndSet(true, false)) {
                inAudit.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(auditoria).registrar(anyString(), anyString(), any(UUID.class), anyMap());
        var a = CompletableFuture.supplyAsync(() -> asJwt("synthetic-admin-a", "admin", "ADMIN", () -> success(r)));
        CompletableFuture<String> b = null;
        try {
            assertThat(inAudit.await(10, TimeUnit.SECONDS)).isTrue();
            b = CompletableFuture.supplyAsync(() -> {
                secondStarted.countDown();
                return asJwt("synthetic-admin-b", "admin", "ADMIN", () -> success(r));
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = b;
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally { release.countDown(); }
        String user = a.get(20, TimeUnit.SECONDS);
        assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(user);
        assertThat(snapshot().users()).hasSize(before.users().size() + 1);
        assertThat(snapshot().doctors()).isEqualTo(before.doctors());
        assertThat(actions(user)).containsExactlyInAnyOrder("USUARIO_CREADO", r.action());
        linked(r, user);
    }
}
