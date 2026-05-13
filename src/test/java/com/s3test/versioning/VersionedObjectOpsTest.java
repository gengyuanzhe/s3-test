package com.s3test.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;

@DisplayName("版本化对象操作测试")
class VersionedObjectOpsTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    private String bucket;
    private S3Client client;

    @BeforeEach
    void setUp() {
        bucket = bucketFixture.createBucket("ver-obj-ops");
        client = bucketFixture.getClient();

        // Enable versioning before each test
        client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
    }

    @Test
    @DisplayName("PUT对象启用版本控制后 - 返回包含versionId")
    void testPutReturnsVersionId() {
        var key = ObjectFixture.randomKey();
        var data = ObjectFixture.randomBytes(ObjectFixture.SIZE_1KB);

        var response = client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(data)
        );

        assertNotNull(response.versionId(), "PUT response should contain versionId");
    }

    @Test
    @DisplayName("覆盖同一key - 创建新版本, 两个versionId不同且都被保留")
    void testOverwriteCreatesNewVersion() {
        var key = ObjectFixture.randomKey();
        var dataV1 = ObjectFixture.randomBytes(ObjectFixture.SIZE_1KB);
        var dataV2 = ObjectFixture.randomBytes(ObjectFixture.SIZE_1KB);

        var v1Response = client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(dataV1)
        );
        var v2Response = client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(dataV2)
        );

        assertNotNull(v1Response.versionId());
        assertNotNull(v2Response.versionId());
        assertNotEquals(v1Response.versionId(), v2Response.versionId(),
                "Two PUTs to same key should produce different versionIds");

        // Both versions preserved: list should show 2 versions for this key
        var listResponse = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build()
        );
        var versions = listResponse.versions().stream()
                .filter(v -> v.key().equals(key))
                .toList();
        assertEquals(2, versions.size(), "Should have 2 versions for the key");
    }

    @Test
    @DisplayName("GET不指定versionId - 返回最新版本内容")
    void testGetWithoutVersionId_ReturnsLatest() {
        var key = ObjectFixture.randomKey();
        var dataV1 = "content-version-1".getBytes();
        var dataV2 = "content-version-2".getBytes();

        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(dataV1)
        );
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(dataV2)
        );

        var content = readObjectBytes(key);
        AssertS3.assertContentEquals(dataV2, content);
    }

    @Test
    @DisplayName("GET指定versionId - 返回该版本的特定内容")
    void testGetWithVersionId_ReturnsSpecificVersion() {
        var key = ObjectFixture.randomKey();
        var dataV1 = "content-version-1".getBytes();
        var dataV2 = "content-version-2".getBytes();

        var v1Response = client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(dataV1)
        );
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(dataV2)
        );

        // Get v1 explicitly
        var content = readObjectBytes(key, v1Response.versionId());
        AssertS3.assertContentEquals(dataV1, content);
    }

    @Test
    @DisplayName("GET指定不存在的versionId - 返回404 NoSuchVersion")
    void testGetWithInvalidVersionId() {
        var key = ObjectFixture.randomKey();
        ObjectHelper.putObject(client, bucket, key, ObjectFixture.randomBytes(ObjectFixture.SIZE_1KB));

        AssertS3.assertThrowsS3Exception(404, "NoSuchVersion", () ->
                client.getObject(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .versionId("nonexistent-version-id-00000")
                        .build())
        );
    }

    @Test
    @DisplayName("HEAD指定versionId - 返回该版本的正确元数据")
    void testHeadWithVersionId() {
        var key = ObjectFixture.randomKey();
        var dataV1 = "head-v1-content".getBytes();
        var dataV2 = "head-v2-content-longer".getBytes();

        var v1Response = client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType("text/plain")
                        .build(),
                RequestBody.fromBytes(dataV1)
        );
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType("application/json")
                        .build(),
                RequestBody.fromBytes(dataV2)
        );

        // HEAD v1
        HeadObjectResponse headV1 = client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .versionId(v1Response.versionId())
                .build());

        assertEquals(dataV1.length, headV1.contentLength());
        assertEquals("text/plain", headV1.contentType());
        assertEquals(v1Response.versionId(), headV1.versionId());
    }

    @Test
    @DisplayName("三次覆盖同一key - ListObjectVersions返回3个版本")
    void testThreeOverwrites_ThreeVersions() {
        var key = ObjectFixture.randomKey();

        for (int i = 0; i < 3; i++) {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(("data-" + i).getBytes())
            );
        }

        var listResponse = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build()
        );
        var versions = listResponse.versions().stream()
                .filter(v -> v.key().equals(key))
                .toList();

        assertEquals(3, versions.size(), "Should have 3 versions for the key");
    }

    // --- helpers ---

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

    private byte[] readObjectBytes(String key, String versionId) {
        try (ResponseInputStream<GetObjectResponse> is = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).versionId(versionId).build())) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            is.transferTo(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read object version: " + key + " v=" + versionId, e);
        }
    }
}
