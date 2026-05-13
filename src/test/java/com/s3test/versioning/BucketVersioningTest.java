package com.s3test.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import com.s3test.config.S3TestConfig;
import com.s3test.fixture.BucketFixture;
import com.s3test.util.AssertS3;

@DisplayName("Bucket Versioning 配置测试")
class BucketVersioningTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("新Bucket默认未启用版本控制 - GetBucketVersioning返回空状态")
    void testDefaultVersioningStatus() {
        var bucket = bucketFixture.createBucket("ver-default");
        var client = bucketFixture.getClient();

        var response = client.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()
        );

        // New bucket should not have versioning enabled; status is null or empty
        assertTrue(response.status() == null
                        || response.status() == BucketVersioningStatus.SUSPENDED
                        || response.status().toString().isEmpty(),
                "New bucket versioning status should not be ENABLED, got: " + response.status());
    }

    @Test
    @DisplayName("启用版本控制 - PutBucketVersioning(ENABLED)后Get返回ENABLED")
    void testEnableVersioning() {
        var bucket = bucketFixture.createBucket("ver-enable");
        var client = bucketFixture.getClient();

        client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());

        var response = client.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()
        );

        assertEquals(BucketVersioningStatus.ENABLED, response.status());
    }

    @Test
    @DisplayName("暂停版本控制 - ENABLED -> SUSPENDED后Get返回SUSPENDED")
    void testSuspendVersioning() {
        var bucket = bucketFixture.createBucket("ver-suspend");
        var client = bucketFixture.getClient();

        // First enable
        client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());

        // Then suspend
        client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.SUSPENDED)
                        .build())
                .build());

        var response = client.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()
        );

        assertEquals(BucketVersioningStatus.SUSPENDED, response.status());
    }

    @Test
    @DisplayName("重复启用版本控制 - 幂等操作仍返回ENABLED")
    void testEnableVersioningIdempotent() {
        var bucket = bucketFixture.createBucket("ver-idempotent");
        var client = bucketFixture.getClient();

        // Enable twice
        for (int i = 0; i < 2; i++) {
            client.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
        }

        var response = client.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()
        );

        assertEquals(BucketVersioningStatus.ENABLED, response.status());
    }

    @Test
    @DisplayName("对不存在的Bucket调用GetBucketVersioning - 返回404 NoSuchBucket")
    void testVersioningOnNonExistentBucket() {
        var nonExistentBucket = S3TestConfig.generateBucketName("noexist-ver");

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                bucketFixture.getClient().getBucketVersioning(
                        GetBucketVersioningRequest.builder()
                                .bucket(nonExistentBucket)
                                .build()
                )
        );
    }
}
