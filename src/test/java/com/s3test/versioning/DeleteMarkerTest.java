package com.s3test.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.util.AssertS3;

@DisplayName("DeleteMarker 测试")
class DeleteMarkerTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    private String bucket;
    private S3Client client;

    @BeforeEach
    void setUp() {
        bucket = bucketFixture.createBucket("ver-del-marker");
        client = bucketFixture.getClient();

        client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
    }

    @Test
    @DisplayName("DELETE版本化对象 - 创建DeleteMarker, 后续GET返回404")
    void testDeleteCreatesDeleteMarker() {
        var key = ObjectFixture.randomKey();
        putObject(key, "data-before-delete".getBytes());

        // Delete creates a delete marker (soft delete)
        DeleteObjectResponse deleteResponse = client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(key).build()
        );
        assertNotNull(deleteResponse.versionId(), "DELETE response should return a versionId (the marker)");
        assertTrue(deleteResponse.deleteMarker(), "Response should indicate a delete marker was created");

        // GET should now return 404
        AssertS3.assertThrowsS3Exception(404, "NoSuchKey", () ->
                client.getObject(GetObjectRequest.builder()
                        .bucket(bucket).key(key).build())
        );
    }

    @Test
    @DisplayName("DeleteMarker保留历史 - 2个版本+DELETE后ListObjectVersions显示2版本+1标记")
    void testDeleteMarkerPreservesHistory() {
        var key = ObjectFixture.randomKey();
        putObject(key, "version-1".getBytes());
        putObject(key, "version-2".getBytes());

        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

        var listResponse = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build()
        );

        var versions = listResponse.versions().stream()
                .filter(v -> v.key().equals(key))
                .toList();
        var markers = listResponse.deleteMarkers().stream()
                .filter(dm -> dm.key().equals(key))
                .toList();

        assertEquals(2, versions.size(), "Should have 2 object versions");
        assertEquals(1, markers.size(), "Should have 1 delete marker");
    }

    @Test
    @DisplayName("删除DeleteMarker可恢复对象 - 删除标记后GET返回最新版本")
    void testDeleteDeleteMarkerRestoresObject() {
        var key = ObjectFixture.randomKey();
        var latestData = "latest-content".getBytes();
        putObject(key, "old-content".getBytes());
        putObject(key, latestData);

        // Create delete marker
        DeleteObjectResponse deleteResponse = client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(key).build()
        );
        String markerVersionId = deleteResponse.versionId();

        // Delete the delete marker itself to restore the object
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(key).versionId(markerVersionId).build());

        // GET should now return the latest version
        var content = readObjectBytes(key);
        AssertS3.assertContentEquals(latestData, content);
    }

    @Test
    @DisplayName("按versionId永久删除 - 指定版本被永久移除, 不会产生DeleteMarker")
    void testPermanentDeleteByVersionId() {
        var key = ObjectFixture.randomKey();
        var putResponse = putObject(key, "to-be-permanently-deleted".getBytes());
        String versionId = putResponse.versionId();

        // Permanent delete by versionId
        DeleteObjectResponse permDeleteResponse = client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucket).key(key).versionId(versionId).build()
        );

        // Verify the version is permanently gone
        var listResponse = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build()
        );
        var remaining = listResponse.versions().stream()
                .filter(v -> v.key().equals(key) && v.versionId().equals(versionId))
                .toList();
        assertTrue(remaining.isEmpty(), "Permanently deleted version should not appear in listing");
    }

    @Test
    @DisplayName("GET使用DeleteMarker的versionId - 返回405 MethodNotAllowed")
    void testGetDeleteMarkerVersion() {
        var key = ObjectFixture.randomKey();
        putObject(key, "data".getBytes());

        // Create delete marker
        DeleteObjectResponse deleteResponse = client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(key).build()
        );
        String markerVersionId = deleteResponse.versionId();

        // GET with the delete marker's versionId should fail
        AssertS3.assertThrowsS3Exception(405, "MethodNotAllowed", () ->
                client.getObject(GetObjectRequest.builder()
                        .bucket(bucket).key(key).versionId(markerVersionId).build())
        );
    }

    // --- helpers ---

    private software.amazon.awssdk.services.s3.model.PutObjectResponse putObject(String key, byte[] data) {
        return client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(data)
        );
    }

    private byte[] readObjectBytes(String key) {
        try (ResponseInputStream<GetObjectResponse> is = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            is.transferTo(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read object: " + key, e);
        }
    }
}
