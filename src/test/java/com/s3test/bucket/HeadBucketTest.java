package com.s3test.bucket;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import com.s3test.fixture.BucketFixture;
import com.s3test.util.AssertS3;

@DisplayName("HeadBucket 操作测试")
class HeadBucketTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("HeadBucket - Bucket存在返回200")
    void testHeadBucket_Exists() {
        var bucketName = bucketFixture.createBucket("head-exist");
        var client = bucketFixture.getClient();

        var response = client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());

        assertEquals(200, response.sdkHttpResponse().statusCode());
    }

    @Test
    @DisplayName("HeadBucket - Bucket不存在返回404")
    void testHeadBucket_NotExists() {
        var client = bucketFixture.getClient();
        var fakeBucket = "s3test-head-noexist-" + System.nanoTime();

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                client.headBucket(HeadBucketRequest.builder().bucket(fakeBucket).build())
        );
    }

    @Test
    @Disabled("需要第二个无权限账号才能测试")
    @DisplayName("HeadBucket - 无权限返回403")
    void testHeadBucket_NoPermission() {
        // Requires a second account without access to this bucket.
        // Skipped by default; enable when multi-account test environment is available.
        var bucketName = bucketFixture.createBucket("head-noperm");
        S3Client noPermClient = null; // would need a restricted-credentials client

        AssertS3.assertThrowsS3Exception(403, "AccessDenied", () ->
                noPermClient.headBucket(HeadBucketRequest.builder().bucket(bucketName).build())
        );
    }
}
