package com.s3test.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Custom assertion utilities for S3 test verification.
 */
public final class AssertS3 {

    private AssertS3() {
    }

    /**
     * Assert that the S3Exception has the expected HTTP status code.
     */
    public static void assertS3Exception(S3Exception exception, int expectedStatusCode) {
        assertEquals(expectedStatusCode, exception.statusCode(),
                () -> "Expected status " + expectedStatusCode + " but got "
                        + exception.statusCode() + " (" + exception.getMessage() + ")");
    }

    /**
     * Assert that the S3Exception has the expected HTTP status code and error code.
     */
    public static void assertS3Exception(S3Exception exception, int expectedStatusCode, String expectedErrorCode) {
        assertS3Exception(exception, expectedStatusCode);
        assertEquals(expectedErrorCode, exception.awsErrorDetails().errorCode(),
                () -> "Expected error code " + expectedErrorCode + " but got "
                        + exception.awsErrorDetails().errorCode());
    }

    /**
     * Assert that a Runnable throws S3Exception with given status and error code.
     */
    public static void assertThrowsS3Exception(int statusCode, String errorCode, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected S3Exception with status " + statusCode
                    + " and error code " + errorCode + ", but no exception was thrown");
        } catch (S3Exception e) {
            assertS3Exception(e, statusCode, errorCode);
        }
    }

    /**
     * Assert that two byte arrays are identical.
     */
    public static void assertContentEquals(byte[] expected, byte[] actual) {
        assertNotNull(actual, "Actual content should not be null");
        assertArrayEquals(expected, actual,
                () -> "Content mismatch: expected " + expected.length + " bytes, got " + actual.length);
    }

    /**
     * Assert that a map contains the expected metadata entries.
     */
    public static void assertMetadataContains(Map<String, String> actual, Map<String, String> expected) {
        assertNotNull(actual, "Metadata should not be null");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertTrue(actual.containsKey(entry.getKey()),
                    () -> "Missing metadata key: " + entry.getKey());
            assertEquals(entry.getValue(), actual.get(entry.getKey()),
                    () -> "Metadata mismatch for key '" + entry.getKey() + "'");
        }
    }

    /**
     * Assert that a bucket name is valid DNS format (lowercase, 3-63 chars, etc.).
     */
    public static void assertValidBucketName(String bucketName) {
        assertNotNull(bucketName, "Bucket name should not be null");
        assertTrue(bucketName.length() >= 3 && bucketName.length() <= 63,
                "Bucket name must be 3-63 characters");
        assertTrue(bucketName.equals(bucketName.toLowerCase()),
                "Bucket name must be lowercase");
        assertTrue(bucketName.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]"),
                "Bucket name must only contain lowercase letters, numbers, dots, and hyphens");
    }
}
