package com.s3test.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.s3test.util.S3ClientFactory;

/**
 * JUnit 5 extension that tracks buckets created during tests and cleans them up after.
 * <p>
 * Usage:
 * <pre>
 * &#64;RegisterExtension
 * static BucketFixture bucketFixture = new BucketFixture();
 *
 * &#64;Test
 * void myTest() {
 *     String bucket = bucketFixture.createBucket("test-prefix");
 *     // use bucket...
 *     // automatic cleanup after test
 * }
 * </pre>
 */
public class BucketFixture implements AfterEachCallback {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final S3Client s3Client;
    private final List<String> createdBuckets = new ArrayList<>();

    public BucketFixture() {
        this.s3Client = S3ClientFactory.create();
    }

    public BucketFixture(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Create a bucket with a unique name and register it for cleanup.
     */
    public String createBucket(String prefix) {
        String bucketName = "s3test-" + prefix + "-" + COUNTER.incrementAndGet() + "-"
                + Long.toHexString(System.nanoTime() & 0xFFFFF);
        s3Client.createBucket(b -> b.bucket(bucketName));
        createdBuckets.add(bucketName);
        return bucketName;
    }

    /**
     * Create a bucket with a specific name and register it for cleanup.
     */
    public String createBucketWithName(String bucketName) {
        s3Client.createBucket(b -> b.bucket(bucketName));
        createdBuckets.add(bucketName);
        return bucketName;
    }

    /**
     * Register an existing bucket for cleanup (without creating it).
     */
    public void register(String bucketName) {
        createdBuckets.add(bucketName);
    }

    /**
     * Get the S3Client used by this fixture.
     */
    public S3Client getClient() {
        return s3Client;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        for (String bucket : createdBuckets) {
            forceDeleteBucket(bucket);
        }
        createdBuckets.clear();
    }

    /**
     * Force delete a bucket including all objects and versions.
     */
    private void forceDeleteBucket(String bucket) {
        try {
            // Delete all object versions (includes delete markers)
            try {
                var versionsResponse = s3Client.listObjectVersions(
                        ListObjectVersionsRequest.builder().bucket(bucket).build()
                );
                List<ObjectIdentifier> toDelete = new ArrayList<>();
                for (ObjectVersion v : versionsResponse.versions()) {
                    toDelete.add(ObjectIdentifier.builder()
                            .key(v.key()).versionId(v.versionId()).build());
                }
                for (var dm : versionsResponse.deleteMarkers()) {
                    toDelete.add(ObjectIdentifier.builder()
                            .key(dm.key()).versionId(dm.versionId()).build());
                }
                if (!toDelete.isEmpty()) {
                    s3Client.deleteObjects(b -> b.bucket(bucket)
                            .delete(d -> d.objects(toDelete).quiet(true)));
                }
            } catch (S3Exception e) {
                // Versioning may not be supported, try regular delete
            }

            // Delete all objects (non-versioned)
            var listResponse = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).build()
            );
            if (!listResponse.contents().isEmpty()) {
                List<ObjectIdentifier> toDelete = listResponse.contents().stream()
                        .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                        .toList();
                s3Client.deleteObjects(b -> b.bucket(bucket)
                        .delete(d -> d.objects(toDelete).quiet(true)));
            }

            // Delete bucket
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            // Best-effort cleanup; don't fail the test
        }
    }
}
