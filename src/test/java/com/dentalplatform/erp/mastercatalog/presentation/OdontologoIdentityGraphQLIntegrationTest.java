package com.dentalplatform.erp.mastercatalog.presentation;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** T3 / S1-S2: public GraphQL contract. No repository, SQL or private identity-column assertions. */
@WithMockUser(username = "admin", roles = "ADMIN")
class OdontologoIdentityGraphQLIntegrationTest extends IntegrationTestBase {

    private static final String SELF = "{ obtenerMiOdontologoId }";
    private static final String BIND = """
            mutation($input: VincularIdentidadOdontologoInput!) {
              vincularIdentidadOdontologo(input: $input)
            }""";
    private static final String UNAVAILABLE = "Identidad odontológica no disponible";
    private static final String CONFLICT = "La identidad odontológica ya está vinculada";

    private record Identity(String user, String doctor, String subject, String username, String email, String name) { }
    private record Snapshot(List<Object> users, List<Object> doctors, List<Object> audits) { }

    private Identity fixture(String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 18);
        String username = "identity-" + suffix;
        String email = username + "@example.test";
        String name = "Identidad " + suffix;
        String user = graphQl.document("""
                mutation($i: CrearUsuarioInput!) { crearUsuario(input: $i) { id username email nombreCompleto rol { codigo } activo } }
                """).variable("i", Map.of("username", username, "email", email, "nombreCompleto", name,
                        "password", "Synthetic2026!", "rolCodigo", role)).execute()
                .path("crearUsuario.username").entity(String.class).isEqualTo(username)
                .path("crearUsuario.email").entity(String.class).isEqualTo(email)
                .path("crearUsuario.nombreCompleto").entity(String.class).isEqualTo(name)
                .path("crearUsuario.rol.codigo").entity(String.class).isEqualTo(role)
                .path("crearUsuario.activo").entity(Boolean.class).isEqualTo(true)
                .path("crearUsuario.id").entity(String.class).get();
        String doctor = graphQl.document("""
                mutation($i: CrearOdontologoInput!) { crearOdontologo(input: $i) { id ci activo } }
                """).variable("i", Map.of("ci", "CI-" + suffix, "matriculaProfesional", "MAT-" + suffix,
                        "nombres", name, "apellidos", "Prueba", "email", email, "telefono", "+59170000000",
                        "porcentajeComision", 30, "especialidadesIds", List.of())).execute()
                .path("crearOdontologo.ci").entity(String.class).isEqualTo("CI-" + suffix)
                .path("crearOdontologo.activo").entity(Boolean.class).isEqualTo(true)
                .path("crearOdontologo.id").entity(String.class).get();
        Identity identity = new Identity(user, doctor, UUID.randomUUID().toString(), username, email, name);
        identities.add(identity);
        return identity;
    }

    private Snapshot snapshot() {
        return new Snapshot(rows("{ listarUsuarios { id username email nombreCompleto activo rol { codigo } } }", "listarUsuarios"),
                rows("{ listarOdontologos(soloActivos: false) { id ci matriculaProfesional nombres apellidos telefono email porcentajeComision activo especialidades { id } } }", "listarOdontologos"),
                rows("{ listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson createdAt } }", "listarAuditLogs"));
    }

    private List<Object> rows(String query, String path) {
        return graphQl.document(query).execute().path(path).entityList(Object.class).get().stream()
                .sorted(java.util.Comparator.comparing(row -> ((Map<?, ?>) row).get("id").toString())).toList();
    }

    private GraphQlTester.Response bind(String user, String doctor, String subject) {
        return graphQl.document(BIND).variable("input", Map.of("usuarioId", user, "odontologoId", doctor,
                "keycloakSubject", subject)).execute();
    }

    private void bind(Identity i) {
        bind(i.user(), i.doctor(), i.subject()).path("vincularIdentidadOdontologo")
                .entity(String.class).isEqualTo(i.doctor());
    }

    private <T> T asJwt(String subject, String username, String role, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("synthetic-service-test").header("alg", "RS256")
                .subject(subject).claim("preferred_username", username).build();
        context.setAuthentication(new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList("ROLE_" + role), username));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void self(Identity i, String role) {
        asJwt(i.subject(), i.username(), role, () -> graphQl.document(SELF).execute())
                .path("obtenerMiOdontologoId").entity(String.class).isEqualTo(i.doctor());
    }

    private void error(GraphQlTester.Response response, String field, ErrorType type, String message) {
        response.errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getErrorType()).isEqualTo(type);
            if (message != null) {
                assertThat(errors.getFirst().getMessage()).isEqualTo(message);
            }
        });
        response.path(field).pathDoesNotExist();
    }

    private void unavailable(String subject, String username) {
        Snapshot before = snapshot();
        error(asJwt(subject, username, "ODONTOLOGO", () -> graphQl.document(SELF).execute()),
                "obtenerMiOdontologoId", ErrorType.NOT_FOUND, UNAVAILABLE);
        assertThat(snapshot()).isEqualTo(before);
    }

    private final List<Identity> identities = new java.util.ArrayList<>();

    private List<Object> selfStates() {
        return identities.stream().map(i -> {
            var response = asJwt(i.subject(), i.username(), "ODONTOLOGO", () -> graphQl.document(SELF).execute());
            var errors = new java.util.ArrayList<String>();
            response.errors().satisfy(found -> found.forEach(e -> {
                assertThat(e.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                assertThat(e.getMessage()).isEqualTo(UNAVAILABLE);
                errors.add(e.getMessage());
            }));
            if (!errors.isEmpty()) {
                assertThat(errors).hasSize(1);
                response.path("obtenerMiOdontologoId").pathDoesNotExist();
                return (Object) List.copyOf(errors);
            }
            return (Object) response.path("obtenerMiOdontologoId").entity(String.class).get();
        }).toList();
    }

    private void rejected(String user, String doctor, String subject, ErrorType type, String message) {
        Snapshot before = snapshot();
        List<Object> selfBefore = selfStates();
        error(bind(user, doctor, subject), "vincularIdentidadOdontologo", type, message);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(selfStates()).isEqualTo(selfBefore);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void newUnboundUserAndDoctorCanBeProvisionedAndBothAllowedJwtRolesResolveCanonicalId() {
        // Database role is deliberately not ODONTOLOGO: T3 only constrains the JWT role for self lookup.
        Identity i = fixture("CAJERO");
        assertThat(i.subject()).isNotEqualTo(i.doctor()).isNotEqualTo(i.user());
        Snapshot before = snapshot();
        unavailable(i.subject(), i.username());
        assertThat(snapshot()).isEqualTo(before);
        bind(i);
        Snapshot linked = snapshot();
        assertThat(linked.users()).isEqualTo(before.users());
        assertThat(linked.doctors()).isEqualTo(before.doctors());
        assertThat(linked.audits().size()).isEqualTo(before.audits().size() + 1);
        self(i, "ODONTOLOGO");
        self(i, "ADMIN");
        assertThat(snapshot()).isEqualTo(linked);
    }

    @Test
    void exactConsistentBindingIsIdempotentWithoutExtraAuditOrPublicChange() {
        Identity i = fixture("ODONTOLOGO");
        bind(i);
        Snapshot before = snapshot();
        bind(i);
        assertThat(snapshot()).isEqualTo(before);
        self(i, "ODONTOLOGO");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void missingUserAndMissingDoctorAreNotFoundWithoutBindingEitherSide() {
        Identity i = fixture("ODONTOLOGO");
        rejected(UUID.randomUUID().toString(), i.doctor(), i.subject(), ErrorType.NOT_FOUND, "Usuario no encontrado");
        unavailable(i.subject(), i.username());
        rejected(i.user(), UUID.randomUUID().toString(), i.subject(), ErrorType.NOT_FOUND, "Odontólogo no encontrado");
        unavailable(i.subject(), i.username());
        bind(i); // Neither rejected attempt reserved the subject or surviving entity.
        self(i, "ODONTOLOGO");
    }

    @Test
    void unknownSubjectAndUnboundSelfFailClosedWithoutUsernameEmailOrNameFallback() {
        Identity i = fixture("ODONTOLOGO");
        Snapshot before = snapshot();
        unavailable(UUID.randomUUID().toString(), "nobody");
        unavailable(i.subject(), i.username());
        unavailable(i.username(), i.username());
        unavailable(i.email(), i.email());
        unavailable(i.name(), i.name());
        assertThat(snapshot()).isEqualTo(before);
        bind(i);
        Snapshot linked = snapshot();
        for (String guessed : List.of(i.username(), i.email(), i.name(), i.user(), i.doctor())) {
            unavailable(guessed, i.username());
        }
        assertThat(snapshot()).isEqualTo(linked);
        self(i, "ODONTOLOGO");
    }

    @Test
    void verifiedSubjectWinsOverAnotherUsersPreferredUsername() {
        Identity a = fixture("ODONTOLOGO");
        Identity b = fixture("ODONTOLOGO");
        bind(a);
        bind(b);
        Snapshot before = snapshot();
        asJwt(a.subject(), b.username(), "ODONTOLOGO", () -> graphQl.document(SELF).execute())
                .path("obtenerMiOdontologoId").entity(String.class).isEqualTo(a.doctor());
        assertThat(snapshot()).isEqualTo(before);
    }

    private void deactivateUser(Identity i) {
        graphQl.document("""
                mutation($id: UUID!) { actualizarUsuario(id: $id, input: {rolCodigo: "ODONTOLOGO", activo: false}) { id activo } }
                """).variable("id", i.user()).execute().path("actualizarUsuario.activo").entity(Boolean.class).isEqualTo(false);
    }

    private void deactivateDoctor(Identity i) {
        graphQl.document("mutation($id: UUID!) { eliminarOdontologo(id: $id) }")
                .variable("id", i.doctor()).execute().path("eliminarOdontologo").entity(Boolean.class).isEqualTo(true);
        assertThat(rows("{ listarOdontologos(soloActivos: false) { id activo } }", "listarOdontologos"))
                .anySatisfy(row -> {
                    Map<?, ?> doctor = (Map<?, ?>) row;
                    assertThat(doctor.get("id")).isEqualTo(i.doctor());
                    assertThat(doctor.get("activo")).isEqualTo(false);
                });
    }

    @Test
    void inactiveUserCannotBeProvisionedAndPreviouslyBoundInactiveUserCannotResolve() {
        Identity unbound = fixture("ODONTOLOGO");
        deactivateUser(unbound);
        rejected(unbound.user(), unbound.doctor(), unbound.subject(), ErrorType.BAD_REQUEST, "El usuario está inactivo");
        unavailable(unbound.subject(), unbound.username());
        Identity bound = fixture("ODONTOLOGO");
        bind(bound);
        deactivateUser(bound);
        Snapshot before = snapshot();
        unavailable(bound.subject(), bound.username());
        assertThat(snapshot()).isEqualTo(before);
        rejected(bound.user(), bound.doctor(), bound.subject(), ErrorType.BAD_REQUEST, "El usuario está inactivo");
    }

    @Test
    void inactiveDoctorCannotBeProvisionedAndPreviouslyBoundInactiveDoctorCannotResolve() {
        Identity unbound = fixture("ODONTOLOGO");
        deactivateDoctor(unbound);
        rejected(unbound.user(), unbound.doctor(), unbound.subject(), ErrorType.BAD_REQUEST, "El odontólogo está inactivo");
        unavailable(unbound.subject(), unbound.username());
        Identity bound = fixture("ODONTOLOGO");
        bind(bound);
        deactivateDoctor(bound);
        Snapshot before = snapshot();
        unavailable(bound.subject(), bound.username());
        assertThat(snapshot()).isEqualTo(before);
        rejected(bound.user(), bound.doctor(), bound.subject(), ErrorType.BAD_REQUEST, "El odontólogo está inactivo");
    }

    @Test
    void subjectOwnedByAnotherUserCannotBeClaimedAndOriginalBindingSurvives() {
        Identity a = fixture("ODONTOLOGO");
        Identity b = fixture("ODONTOLOGO");
        bind(a);
        rejected(b.user(), b.doctor(), a.subject(), ErrorType.BAD_REQUEST, CONFLICT);
        self(a, "ODONTOLOGO");
        unavailable(b.subject(), b.username());
        bind(b);
        self(b, "ODONTOLOGO");
    }

    @Test
    void doctorOwnedByAnotherUserCannotBeClaimedAndOriginalBindingSurvives() {
        Identity a = fixture("ODONTOLOGO");
        Identity b = fixture("ODONTOLOGO");
        bind(a);
        rejected(b.user(), a.doctor(), b.subject(), ErrorType.BAD_REQUEST, CONFLICT);
        self(a, "ODONTOLOGO");
        unavailable(b.subject(), b.username());
        bind(b);
        self(b, "ODONTOLOGO");
    }

    @Test
    void boundUserCannotChangeDoctorOrSubjectOrBoth() {
        Identity a = fixture("ODONTOLOGO");
        Identity b = fixture("ODONTOLOGO");
        bind(a);
        rejected(a.user(), b.doctor(), a.subject(), ErrorType.BAD_REQUEST, CONFLICT);
        rejected(a.user(), a.doctor(), b.subject(), ErrorType.BAD_REQUEST, CONFLICT);
        rejected(a.user(), b.doctor(), b.subject(), ErrorType.BAD_REQUEST, CONFLICT);
        self(a, "ODONTOLOGO");
        unavailable(b.subject(), b.username());
        bind(b);
        self(b, "ODONTOLOGO");
    }

    @Test
    void twoExistingBindingsCannotBeCrossedOrSwapped() {
        Identity a = fixture("ODONTOLOGO");
        Identity b = fixture("ODONTOLOGO");
        bind(a);
        bind(b);
        rejected(a.user(), b.doctor(), b.subject(), ErrorType.BAD_REQUEST, CONFLICT);
        rejected(b.user(), a.doctor(), a.subject(), ErrorType.BAD_REQUEST, CONFLICT);
        self(a, "ODONTOLOGO");
        self(b, "ODONTOLOGO");
    }

    @Test
    void cashierAndReceptionistCannotLookupAndNoNonAdminRoleCanProvision() {
        Identity i = fixture("ODONTOLOGO");
        Snapshot before = snapshot();
        for (String role : List.of("CAJERO", "RECEPCIONISTA", "ODONTOLOGO")) {
            error(asJwt(i.subject(), i.username(), role, () -> bind(i.user(), i.doctor(), i.subject())),
                    "vincularIdentidadOdontologo", ErrorType.FORBIDDEN, null);
            assertThat(snapshot()).isEqualTo(before);
        }
        bind(i);
        Snapshot linked = snapshot();
        for (String role : List.of("CAJERO", "RECEPCIONISTA")) {
            error(asJwt(i.subject(), i.username(), role, () -> graphQl.document(SELF).execute()),
                    "obtenerMiOdontologoId", ErrorType.FORBIDDEN, null);
            assertThat(snapshot()).isEqualTo(linked);
        }
        self(i, "ODONTOLOGO");
    }

    @Test
    void nonJwtPrincipalCannotUseUsernameAsSubjectEvenWithAdminRole() {
        Identity i = fixture("ODONTOLOGO");
        bind(i.user(), i.doctor(), "admin").path("vincularIdentidadOdontologo")
                .entity(String.class).isEqualTo(i.doctor());
        Snapshot before = snapshot();
        error(graphQl.document(SELF).execute(), "obtenerMiOdontologoId", ErrorType.FORBIDDEN,
                "Se requiere un JWT con subject");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void opaqueSubjectKeepsCaseAndWhitespaceAndEnforcesBlankAndLengthBoundaries() {
        Identity i = fixture("ODONTOLOGO");
        String invalidMessage = "El subject de Keycloak debe tener entre 1 y 100 caracteres y no estar en blanco";
        for (String invalid : List.of("", "   ", "X".repeat(101))) {
            rejected(i.user(), i.doctor(), invalid, ErrorType.BAD_REQUEST, invalidMessage);
        }
        String prefix = " Opaque:" + UUID.randomUUID() + ":Case";
        String subject = prefix + "X".repeat(99 - prefix.length()) + " ";
        assertThat(subject).hasSize(100);
        bind(i.user(), i.doctor(), subject).path("vincularIdentidadOdontologo")
                .entity(String.class).isEqualTo(i.doctor());
        Snapshot before = snapshot();
        asJwt(subject, i.username(), "ODONTOLOGO", () -> graphQl.document(SELF).execute())
                .path("obtenerMiOdontologoId").entity(String.class).isEqualTo(i.doctor());
        unavailable(subject.trim(), i.username());
        unavailable(subject.toLowerCase(java.util.Locale.ROOT), i.username());
        bind(i.user(), i.doctor(), subject).path("vincularIdentidadOdontologo")
                .entity(String.class).isEqualTo(i.doctor());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ordinaryUserAndDoctorUpdatesPreserveLinksAndUserReactivationRestoresSelfLookup() {
        Identity i = fixture("ODONTOLOGO");
        bind(i);
        graphQl.document("""
                mutation($id: UUID!) { actualizarUsuario(id: $id,
                  input: {rolCodigo: "CAJERO", activo: true, nombreCompleto: "Nombre editado"}) {
                  id nombreCompleto activo rol { codigo } } }
                """).variable("id", i.user()).execute()
                .path("actualizarUsuario.id").entity(String.class).isEqualTo(i.user())
                .path("actualizarUsuario.nombreCompleto").entity(String.class).isEqualTo("Nombre editado")
                .path("actualizarUsuario.rol.codigo").entity(String.class).isEqualTo("CAJERO");
        self(i, "ODONTOLOGO");
        Map<?, ?> doctor = (Map<?, ?>) rows("""
                { listarOdontologos(soloActivos: false) { id ci matriculaProfesional nombres apellidos } }
                """, "listarOdontologos").stream()
                .filter(row -> i.doctor().equals(((Map<?, ?>) row).get("id"))).findFirst().orElseThrow();
        graphQl.document("""
                mutation($id: UUID!, $input: ActualizarOdontologoInput!) {
                  actualizarOdontologo(id: $id, input: $input) { id telefono activo } }
                """).variable("id", i.doctor()).variable("input", Map.of("ci", doctor.get("ci"),
                        "matriculaProfesional", doctor.get("matriculaProfesional"), "nombres", doctor.get("nombres"),
                        "apellidos", doctor.get("apellidos"), "telefono", "+59171111111", "email", i.email(),
                        "porcentajeComision", 35, "especialidadesIds", List.of())).execute()
                .path("actualizarOdontologo.id").entity(String.class).isEqualTo(i.doctor())
                .path("actualizarOdontologo.telefono").entity(String.class).isEqualTo("+59171111111")
                .path("actualizarOdontologo.activo").entity(Boolean.class).isEqualTo(true);
        self(i, "ODONTOLOGO");
        deactivateUser(i);
        unavailable(i.subject(), i.username());
        graphQl.document("""
                mutation($id: UUID!) { actualizarUsuario(id: $id,
                  input: {rolCodigo: "ODONTOLOGO", activo: true}) { activo } }
                """).variable("id", i.user()).execute()
                .path("actualizarUsuario.activo").entity(Boolean.class).isEqualTo(true);
        self(i, "ODONTOLOGO");
        Snapshot before = snapshot();
        bind(i);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void concurrentSubjectClaimsHaveOneWinnerOneAuditAndNoPartialLoserBinding() throws Exception {
        Identity a = fixture("ODONTOLOGO");
        Identity b = fixture("ODONTOLOGO");
        String subject = "concurrent:" + UUID.randomUUID();
        Snapshot before = snapshot();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> concurrentClaim(a, subject, ready, start));
        var second = CompletableFuture.supplyAsync(() -> concurrentClaim(b, subject, ready, start));
        boolean bothReady = ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        assertThat(bothReady).isTrue();
        boolean aWon = first.get(20, TimeUnit.SECONDS);
        boolean bWon = second.get(20, TimeUnit.SECONDS);
        assertThat(List.of(aWon, bWon)).containsExactlyInAnyOrder(true, false);
        Snapshot after = snapshot();
        assertThat(after.users()).isEqualTo(before.users());
        assertThat(after.doctors()).isEqualTo(before.doctors());
        assertThat(after.audits().size()).isEqualTo(before.audits().size() + 1);
        Identity winner = aWon ? a : b;
        Identity loser = aWon ? b : a;
        asJwt(subject, loser.username(), "ODONTOLOGO", () -> graphQl.document(SELF).execute())
                .path("obtenerMiOdontologoId").entity(String.class).isEqualTo(winner.doctor());
        unavailable(loser.subject(), loser.username());
        assertThat(snapshot()).isEqualTo(after);
        // A unique-constraint loser must not retain either half of the failed association.
        bind(loser);
        self(loser, "ODONTOLOGO");
        asJwt(subject, winner.username(), "ODONTOLOGO", () -> graphQl.document(SELF).execute())
                .path("obtenerMiOdontologoId").entity(String.class).isEqualTo(winner.doctor());
    }

    private boolean concurrentClaim(Identity i, String subject, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        var response = asJwt("admin-concurrent", "admin", "ADMIN", () -> bind(i.user(), i.doctor(), subject));
        boolean[] rejected = {false};
        response.errors().satisfy(errors -> {
            assertThat(errors.size()).isLessThanOrEqualTo(1);
            errors.forEach(e -> {
                assertThat(e.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(e.getMessage()).isIn(CONFLICT, "El registro viola una restricción de unicidad o integridad");
                rejected[0] = true;
            });
        });
        if (rejected[0]) {
            response.path("vincularIdentidadOdontologo").pathDoesNotExist();
        } else {
            response.path("vincularIdentidadOdontologo").entity(String.class).isEqualTo(i.doctor());
        }
        return !rejected[0];
    }

    @Test
    void selfQueryHasNoCallerSelectableSubjectArgument() {
        Identity i = fixture("ODONTOLOGO");
        bind(i);
        Snapshot before = snapshot();
        var response = graphQl.document("query($s: String!) { obtenerMiOdontologoId(subject: $s) }")
                .variable("s", i.subject()).execute();
        response.errors().satisfy(errors -> assertThat(errors).anySatisfy(e ->
                assertThat(e.getMessage()).contains("UnknownArgument").contains("subject")));
        assertThat(snapshot()).isEqualTo(before);
        self(i, "ODONTOLOGO");
    }
}
