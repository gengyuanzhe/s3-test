package com.s3test.util;

import java.net.URI;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import com.s3test.config.S3TestConfig;

/**
 * Factory for creating S3Client instances configured for the target S3-compatible system.
 */
public final class S3ClientFactory {

    private S3ClientFactory() {
    }

    /**
     * Create a default S3Client using test configuration from system properties.
     */
    public static S3Client create() {
        return S3Client.builder()
                .endpointOverride(S3TestConfig.getEndpointUri())
                .region(Region.of(S3TestConfig.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                S3TestConfig.getAccessKey(),
                                S3TestConfig.getSecretKey()
                        )
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    /**
     * Create an S3Client with custom credentials (for multi-account tests).
     */
    public static S3Client create(String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(S3TestConfig.getEndpointUri())
                .region(Region.of(S3TestConfig.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    /**
     * Create an S3Client pointing to a different endpoint.
     */
    public static S3Client create(String endpoint, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(S3TestConfig.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }
}
