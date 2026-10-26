package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.financebilling.infrastructure.document.ReceiptStorageConfig.Minio;
import io.minio.credentials.*;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Deterministic AWS-mode wiring: no live AWS, IMDS or STS call is made. */
class AwsReceiptStorageTest {
    static Minio aws(String accessKey, String secretKey, String region, String endpoint) {
        return new Minio(endpoint, accessKey, secretKey, "private-receipts", region, 300, 1000, 1000, 1000, 1000, "aws");
    }
    static ReceiptStoragePort storage(Minio config) {
        return new ReceiptStorageConfig().receiptStorage(new ReceiptStorageConfig.Settings("s3", "unused", config));
    }

    @Test void awsChainIsEnvironmentThenSharedConfigThenIamRoleOrWebIdentity() {
        var chain = ReceiptStorageConfig.awsChain(new OkHttpClient());
        assertThat(chain.stream().map(Object::getClass).toList())
                .containsExactly(AwsEnvironmentProvider.class, AwsConfigProvider.class, IamAwsProvider.class);
    }

    @Test void staticKeysWinAndAwsWithoutKeysUsesTheChain() {
        var http = new OkHttpClient();
        assertThat(ReceiptStorageConfig.credentialsProvider(aws("k", "s", "eu-west-1", null), http)).isInstanceOf(StaticProvider.class);
        assertThat(ReceiptStorageConfig.credentialsProvider(aws(null, null, "eu-west-1", null), http)).isInstanceOf(ChainedProvider.class);
        assertThat(ReceiptStorageConfig.credentialsProvider(aws("", " ", "eu-west-1", null), http)).isInstanceOf(ChainedProvider.class);
    }

    @Test void awsRequiresRegionAndRejectsHalfConfiguredKeysOrInsecureEndpoint() {
        for (String region : new String[]{null, "", "not a region", "eu_west_1"})
            assertThatThrownBy(() -> storage(aws(null, null, region, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage(aws("only-access", null, "eu-west-1", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage(aws(null, "only-secret", "eu-west-1", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage(aws(null, null, "eu-west-1", "http://s3.eu-west-1.amazonaws.com"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage(aws(null, null, "eu-west-1", null))).isNotNull();
    }

    @Test void s3CompatibleStillRequiresStaticKeysAndKeepsDefaultRegion() {
        var noKeys = new Minio("http://localhost:8333", null, null, "private-receipts", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> storage(noKeys)).isInstanceOf(IllegalArgumentException.class);
        var legacy = new Minio("http://localhost:8333", "a", "b", "private-receipts", null, null, null, null, null, null);
        assertThat(legacy.region()).isEqualTo("us-east-1");
        assertThat(legacy.provider()).isEqualTo("s3-compatible");
    }

    @Test void awsEndpointDefaultsToRegionalHost() {
        assertThat(aws(null, null, "eu-west-1", null).effectiveEndpoint()).isEqualTo("https://s3.eu-west-1.amazonaws.com");
        assertThat(aws(null, null, "eu-west-1", "https://s3.eu-west-1.amazonaws.com").effectiveEndpoint())
                .isEqualTo("https://s3.eu-west-1.amazonaws.com");
    }

    @Test void presignedUrlCarriesConfiguredRegionAndRoleCredentialsNotStaticKeys() {
        var fetches = new AtomicInteger();
        Provider role = () -> { fetches.incrementAndGet(); return new Credentials("ASIAROLEKEY", "role-secret", "role-session-token", null); };
        var config = aws(null, null, "eu-west-1", null);
        var storage = ReceiptStorageConfig.create(config, role);
        UUID id = UUID.randomUUID();
        URI url = URI.create(storage.resolverDescarga(id, "s3://private-receipts/" + id + ".pdf"));
        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("private-receipts.s3.eu-west-1.amazonaws.com");
        assertThat(url.getPath()).isEqualTo("/" + id + ".pdf");
        assertThat(url.getRawQuery()).contains("X-Amz-Credential=ASIAROLEKEY%2F", "%2Feu-west-1%2Fs3%2Faws4_request",
                "X-Amz-Expires=300", "X-Amz-Security-Token=role-session-token", "X-Amz-Signature=")
                .doesNotContain("role-secret");
        assertThat(fetches).hasValue(1);
    }
}
