package com.s3test.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;

@DisplayName("ListObjectVersions 测试")
class ListObjectVersionsTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    private String bucket;
    private S3Client client;

    @BeforeEach
    void setUp() {
        bucket = bucketFixture.createBucket("ver-list");
        client = bucketFixture.getClient();

        client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
    }

    @Test
    @DisplayName("ListObjectVersions - 同一key的3个版本全部列出")
    void testListAllVersions() {
        var key = ObjectFixture.randomKey();

        for (int i = 0; i < 3; i++) {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(("data-" + i).getBytes())
            );
        }

        var response = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).build()
        );

        var versions = response.versions().stream()
                .filter(v -> v.key().equals(key))
                .toList();

        assertEquals(3, versions.size(), "Should list all 3 versions of the key");
        // Each version should have a non-null versionId
        versions.forEach(v -> assertNotNull(v.versionId(), "Each version should have a versionId"));
    }

    @Test
    @DisplayName("ListObjectVersions - 按prefix过滤版本")
    void testListVersionsWithPrefix() {
        var prefixA = "docs/";
        var prefixB = "images/";
        var keyA1 = prefixA + "file1.txt";
        var keyA2 = prefixA + "file2.txt";
        var keyB1 = prefixB + "photo1.jpg";

        ObjectHelper.putObject(client, bucket, keyA1, ObjectFixture.randomBytes(100));
        ObjectHelper.putObject(client, bucket, keyA1, ObjectFixture.randomBytes(200)); // 2nd version
        ObjectHelper.putObject(client, bucket, keyA2, ObjectFixture.randomBytes(100));
        ObjectHelper.putObject(client, bucket, keyB1, ObjectFixture.randomBytes(300));

        var response = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(prefixA).build()
        );

        // All returned versions should have the "docs/" prefix
        response.versions().forEach(v ->
                assertTrue(v.key().startsWith(prefixA),
                        "Version key should start with prefix " + prefixA + ", got: " + v.key())
        );

        // Should have: keyA1 (2 versions) + keyA2 (1 version) = 3 versions
        assertEquals(3, response.versions().size(), "Should list 3 versions with docs/ prefix");
    }

    @Test
    @DisplayName("ListObjectVersions - 使用keyMarker和versionIdMarker分页")
    void testListVersionsWithKeyMarker() {
        var key1 = "marker/key-a";
        var key2 = "marker/key-b";
        var key3 = "marker/key-c";

        var v1Resp = client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key1).build(),
                RequestBody.fromBytes("a1".getBytes())
        );
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key1).build(),
                RequestBody.fromBytes("a2".getBytes())
        );
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key2).build(),
                RequestBody.fromBytes("b1".getBytes())
        );
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key3).build(),
                RequestBody.fromBytes("c1".getBytes())
        );

        // First page: max-keys=2
        var page1 = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(bucket).prefix("marker/").maxKeys(2).build());

        assertTrue(page1.isTruncated(), "First page should be truncated");
        assertEquals(2, page1.versions().size());

        // Use keyMarker + versionIdMarker from the last entry of page1
        var lastEntry = page1.versions().get(page1.versions().size() - 1);
        var page2 = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(bucket)
                .prefix("marker/")
                .keyMarker(lastEntry.key())
                .versionIdMarker(lastEntry.versionId())
                .build());

        // Page 2 should contain the remaining versions
        assertFalse(page2.versions().isEmpty(), "Second page should contain remaining versions");
    }

    @Test
    @DisplayName("ListObjectVersions - maxKeys分页控制")
    void testListVersionsWithMaxKeys() {
        // Create 5 versions across multiple keys
        for (int i = 0; i < 5; i++) {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key("page/obj-" + i).build(),
                    RequestBody.fromBytes(("data-" + i).getBytes())
            );
        }

        // Request with maxKeys=2
        var response = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(bucket).prefix("page/").maxKeys(2).build());

        assertEquals(2, response.versions().size(), "Should return at most maxKeys versions");
        assertTrue(response.isTruncated(), "Response should indicate more results available");
        assertNotNull(response.nextKeyMarker(), "Should provide nextKeyMarker for pagination");
    }

    @Test
    @DisplayName("ListObjectVersions - 未启用版本控制的Bucket仍可调用, versionId为null")
    void testListVersionsOnNonVersionedBucket() {
        // Create a fresh bucket WITHOUT enabling versioning
        var nonVersionedBucket = bucketFixture.createBucket("ver-list-nover");
        var key = ObjectFixture.randomKey();

        ObjectHelper.putObject(client, nonVersionedBucket, key, ObjectFixture.randomBytes(100));

        var response = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(nonVersionedBucket).build()
        );

        // Should succeed and return the object with null versionId
        assertFalse(response.versions().isEmpty(), "Should list objects in non-versioned bucket");
        var obj = response.versions().stream()
                .filter(v -> v.key().equals(key))
                .findFirst()
                .orElse(null);
        assertNotNull(obj, "Should find the uploaded object");
        // On non-versioned bucket, versionId is typically "null" string or null
        assertTrue(obj.versionId() == null || "null".equals(obj.versionId()),
                "Non-versioned object versionId should be null, got: " + obj.versionId());
    }
}
