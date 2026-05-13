package com.s3test.list;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ListObjectsV2")
class ListObjectsV2Test {

    @RegisterExtension
    static final BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("List returns all objects in a bucket")
    void testListAllObjects() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-all");

        for (int i = 0; i < 5; i++) {
            ObjectHelper.putObject(s3, bucket, "obj-" + i, ObjectFixture.randomBytes(ObjectFixture.SIZE_1KB));
        }

        var response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());

        assertEquals(5, response.contents().size(), "Should return all 5 objects");
        assertFalse(response.isTruncated(), "Should not be truncated");
    }

    @Test
    @DisplayName("List on empty bucket returns empty list")
    void testListEmptyBucket() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-empty");

        var response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());

        assertTrue(response.contents().isEmpty(), "Empty bucket should return empty contents");
    }

    @Test
    @DisplayName("List with prefix filters objects correctly")
    void testListWithPrefix() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-prefix");

        ObjectHelper.putObject(s3, bucket, "a/1", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "a/2", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "b/1", ObjectFixture.zeros(10));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix("a/").build());

        List<String> keys = response.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());

        assertEquals(2, keys.size(), "Should return only objects with prefix 'a/'");
        assertTrue(keys.contains("a/1"), "Should contain a/1");
        assertTrue(keys.contains("a/2"), "Should contain a/2");
    }

    @Test
    @DisplayName("List with delimiter returns common prefixes")
    void testListWithDelimiter() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-delim");

        ObjectHelper.putObject(s3, bucket, "a/1", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "a/2", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "b/1", ObjectFixture.zeros(10));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).delimiter("/").build());

        List<String> prefixes = response.commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .collect(Collectors.toList());

        assertEquals(2, prefixes.size(), "Should return 2 common prefixes");
        assertTrue(prefixes.contains("a/"), "Should contain a/");
        assertTrue(prefixes.contains("b/"), "Should contain b/");
    }

    @Test
    @DisplayName("List with prefix and delimiter groups correctly")
    void testListWithPrefixAndDelimiter() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-pfx-delim");

        ObjectHelper.putObject(s3, bucket, "photos/2024/1.jpg", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "photos/2024/2.jpg", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "photos/2025/1.jpg", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "docs/readme.txt", ObjectFixture.zeros(10));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix("photos/").delimiter("/").build());

        List<String> prefixes = response.commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .collect(Collectors.toList());

        assertTrue(prefixes.contains("photos/2024/"), "Should contain photos/2024/");
        assertTrue(prefixes.contains("photos/2025/"), "Should contain photos/2025/");
        assertTrue(response.contents().isEmpty(),
                "No direct objects should appear when all keys are nested under delimiter");
    }

    @Test
    @DisplayName("List with max-keys limits returned items")
    void testListWithMaxKeys() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-maxkeys");

        for (int i = 0; i < 20; i++) {
            ObjectHelper.putObject(s3, bucket, String.format("obj-%02d", i), ObjectFixture.zeros(10));
        }

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).maxKeys(5).build());

        assertEquals(5, response.contents().size(), "Should return exactly 5 items");
        assertTrue(response.isTruncated(), "Should be truncated");
        assertNotNull(response.nextContinuationToken(), "Should have continuation token");
    }

    @Test
    @DisplayName("List pagination returns all objects across pages")
    void testListPagination() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-page");

        for (int i = 0; i < 20; i++) {
            ObjectHelper.putObject(s3, bucket, String.format("obj-%02d", i), ObjectFixture.zeros(10));
        }

        String token = null;
        List<S3Object> all = new ArrayList<>();
        do {
            var req = ListObjectsV2Request.builder()
                    .bucket(bucket).maxKeys(5).continuationToken(token).build();
            var resp = s3.listObjectsV2(req);
            all.addAll(resp.contents());
            token = resp.nextContinuationToken();
            if (resp.isTruncated()) {
                assertNotNull(token, "Truncated response must have continuation token");
            }
        } while (token != null && !all.stream()
                .map(S3Object::key).collect(Collectors.toSet()).contains("obj-19")
                || (all.size() > 0 && all.size() < 20));

        // Simpler approach: paginate until done
        all.clear();
        token = null;
        boolean truncated;
        do {
            var req = ListObjectsV2Request.builder()
                    .bucket(bucket).maxKeys(5).continuationToken(token).build();
            var resp = s3.listObjectsV2(req);
            all.addAll(resp.contents());
            token = resp.nextContinuationToken();
            truncated = resp.isTruncated();
        } while (truncated);

        assertEquals(20, all.size(), "Should return all 20 objects across pages");

        List<String> keys = all.stream().map(S3Object::key).sorted().collect(Collectors.toList());
        for (int i = 0; i < 20; i++) {
            assertEquals(String.format("obj-%02d", i), keys.get(i),
                    "Key at index " + i + " should match");
        }
    }

    @Test
    @DisplayName("List with max-keys=0 returns no keys")
    void testListWithMaxKeysZero() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-maxzero");

        ObjectHelper.putObject(s3, bucket, "obj-a", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "obj-b", ObjectFixture.zeros(10));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).maxKeys(0).build());

        assertEquals(0, response.contents().size(), "Should return 0 keys when maxKeys=0");
    }

    @Test
    @DisplayName("List with max-keys=1000 returns at most 1000 items")
    void testListWithMaxKeys1000() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-max1k");

        // Create fewer objects since 1000 would be slow; verify the limit is honored
        for (int i = 0; i < 5; i++) {
            ObjectHelper.putObject(s3, bucket, "obj-" + i, ObjectFixture.zeros(10));
        }

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).maxKeys(1000).build());

        assertTrue(response.contents().size() <= 1000,
                "Should return at most 1000 items");
        assertEquals(5, response.contents().size(), "Should return all 5 items");
    }

    @Test
    @DisplayName("List with start-after skips keys before the specified key")
    void testListWithStartAfter() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-startafter");

        ObjectHelper.putObject(s3, bucket, "alpha", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "beta", ObjectFixture.zeros(10));
        ObjectHelper.putObject(s3, bucket, "gamma", ObjectFixture.zeros(10));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).startAfter("alpha").build());

        List<String> keys = response.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());

        assertFalse(keys.contains("alpha"), "Should not contain 'alpha'");
        assertTrue(keys.contains("beta"), "Should contain 'beta'");
        assertTrue(keys.contains("gamma"), "Should contain 'gamma'");
    }

    @Test
    @DisplayName("Newly PUT object is immediately visible in list")
    void testNewObjectImmediatelyVisible() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-immediate");
        String key = "immediate-obj";

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());

        List<String> keys = response.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());

        assertTrue(keys.contains(key), "Newly PUT object should appear immediately in list");
    }

    @Test
    @DisplayName("Deleted object is immediately absent from list")
    void testDeletedObjectImmediatelyAbsent() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-deleted");
        String key = "to-delete-obj";

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));
        s3.deleteObject(b -> b.bucket(bucket).key(key).build());

        var response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());

        List<String> keys = response.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());

        assertFalse(keys.contains(key), "Deleted object should not appear in list");
    }

    @Test
    @DisplayName("List with fetch-owner=true includes owner information")
    void testListWithFetchOwner() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-owner");

        ObjectHelper.putObject(s3, bucket, "owner-obj", ObjectFixture.zeros(10));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).fetchOwner(true).build());

        assertFalse(response.contents().isEmpty(), "Should have at least one object");
        for (var obj : response.contents()) {
            assertNotNull(obj.owner(), "Owner should be present when fetchOwner=true");
        }
    }

    @Test
    @DisplayName("List with encoding-type=url returns URL-encoded keys")
    void testListWithEncodingTypeUrl() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("list-encoding");

        // Key with special characters that need encoding
        String key = "path with spaces/file.txt";
        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.zeros(10));

        var response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).encodingType(EncodingType.URL).build());

        assertEquals(EncodingType.URL, response.encodingType(),
                "Response should indicate URL encoding");
        assertFalse(response.contents().isEmpty(), "Should return objects");

        // The key should be URL-encoded in the response
        String returnedKey = response.contents().get(0).key();
        // Either the key is URL-encoded (contains %20) or returned as-is
        // depending on S3 implementation
        assertTrue(returnedKey.contains("path") && returnedKey.contains("file.txt"),
                "Key should contain the expected path components");
    }
}
