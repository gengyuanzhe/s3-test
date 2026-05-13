package com.s3test.config;

import java.net.URI;

/**
 * S3 test configuration loaded from system properties.
 * <p>
 * Usage: {@code mvn test -Ds3.endpoint=http://localhost:9000 -Ds3.accessKey=minio -Ds3.secretKey=minio123}
 */
public final class S3TestConfig {

    private S3TestConfig() {
    }

    public static String getEndpoint() {
        return System.getProperty("s3.endpoint", "http://localhost:9000");
    }

    public static URI getEndpointUri() {
        return URI.create(getEndpoint());
    }

    public static String getRegion() {
        return System.getProperty("s3.region", "us-east-1");
    }

    public static String getAccessKey() {
        return System.getProperty("s3.accessKey", "test-access-key");
    }

    public static String getSecretKey() {
        return System.getProperty("s3.secretKey", "test-secret-key");
    }

    /**
     * Generate a unique bucket name for testing.
     * Format: s3test-{prefix}-{random_hex}
     */
    public static String generateBucketName(String prefix) {
        String random = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        return "s3test-" + prefix + "-" + random;
    }

    /**
     * Generate a unique bucket name with default prefix.
     */
    public static String generateBucketName() {
        return generateBucketName("bucket");
    }
}
