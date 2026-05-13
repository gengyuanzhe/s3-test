package com.s3test.fixture;

import java.util.Map;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Helper for creating test objects in S3 buckets.
 */
public final class ObjectHelper {

    private ObjectHelper() {
    }

    /**
     * Upload a test object and return its key.
     */
    public static String putObject(S3Client s3Client, String bucket, String key, byte[] data) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(data)
        );
        return key;
    }

    /**
     * Upload a test object with metadata and return its key.
     */
    public static String putObjectWithMetadata(S3Client s3Client, String bucket, String key,
                                               byte[] data, Map<String, String> metadata) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).metadata(metadata).build(),
                RequestBody.fromBytes(data)
        );
        return key;
    }

    /**
     * Upload a test object with a specific content type.
     */
    public static String putObjectWithContentType(S3Client s3Client, String bucket, String key,
                                                  byte[] data, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(data)
        );
        return key;
    }
}
