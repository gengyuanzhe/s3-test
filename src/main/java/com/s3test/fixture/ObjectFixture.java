package com.s3test.fixture;

import java.util.Random;
import java.util.UUID;

/**
 * Generates test data for object operations.
 */
public final class ObjectFixture {

    private static final Random RANDOM = new Random();

    private ObjectFixture() {
    }

    /**
     * Generate random bytes of given size.
     */
    public static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        RANDOM.nextBytes(data);
        return data;
    }

    /**
     * Generate all-zero bytes of given size.
     */
    public static byte[] zeros(int size) {
        return new byte[size];
    }

    /**
     * Generate sequential bytes (0x00-0xFF repeating).
     */
    public static byte[] sequentialBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 256);
        }
        return data;
    }

    /**
     * Generate a random object key.
     */
    public static String randomKey() {
        return "test-obj-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Generate a random object key with prefix.
     */
    public static String randomKey(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Generate a random string of given length.
     */
    public static String randomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // Common sizes
    public static final int SIZE_1KB = 1024;
    public static final int SIZE_1MB = 1024 * 1024;
    public static final int SIZE_5MB = 5 * 1024 * 1024;
    public static final int SIZE_10MB = 10 * 1024 * 1024;
}
