package com.fintracker.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Supplies the {@link S3Presigner} bean, split by profile rather than by a single runtime
 * {@code if} — so a local-only S3 emulator (LocalStack) can never end up wired into a real
 * deployment just because a stray environment variable is set. {@link #productionS3Presigner}
 * has no code path that could ever read {@code aws.s3.endpoint-override}; only
 * {@link #localS3Presigner}, gated to the {@code dev}/{@code test} profiles, does. A production
 * deployment misconfigured with {@code AWS_S3_ENDPOINT_OVERRIDE} set would still get the real-AWS
 * bean and simply ignore it — for it to matter, both the wrong profile AND the wrong env var
 * would have to be set, not just one. See docs/instructions/setup_local_s3.md.
 */
@Configuration
public class S3PresignerConfig {

    @Bean
    @Profile("!dev & !test")
    public S3Presigner productionS3Presigner(@Value("${aws.region}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    @Profile("dev | test")
    public S3Presigner localS3Presigner(
            @Value("${aws.region}") String region,
            @Value("${aws.s3.endpoint-override}") String endpointOverride
    ) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpointOverride))
                // LocalStack doesn't validate credentials; "test"/"test" is its documented
                // convention for a local-only placeholder. Never used against real AWS.
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                // Without this, the SDK defaults to virtual-hosted-style addressing (bucket name
                // as a DNS subdomain of the endpoint, e.g. "my-bucket.localhost:4566") —
                // LocalStack has no wildcard DNS for that, so every presigned URL would be
                // unreachable. Path-style ("localhost:4566/my-bucket/...") is LocalStack's own
                // documented requirement for a custom endpoint.
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
