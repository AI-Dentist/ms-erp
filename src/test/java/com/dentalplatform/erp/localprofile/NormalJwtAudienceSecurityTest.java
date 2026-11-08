package com.dentalplatform.erp.localprofile;

import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NormalJwtAudienceSecurityTest extends JwtAudienceSecurityTest {
    private static final EmbeddedOidc OIDC = EmbeddedOidc.started();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        properties(registry, "erp_audience_normal", OIDC, false);
    }

    @Override EmbeddedOidc oidc() { return OIDC; }
    @Override boolean local() { return false; }
    @AfterAll static void stopIssuer() { OIDC.close(); }
}
