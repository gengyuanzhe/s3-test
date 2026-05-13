package com.s3test.bucket;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;

@DisplayName("DeleteBucket 操作测试")
class DeleteBucketTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("删除空Bucket - 成功返回204")
    void testDeleteEmptyBucket_Success() {
        var bucketName = bucketFixture.createBucket("del-empty");
        var client = bucketFixture.getClient();

        // Remove from fixture tracking so we can delete it ourselves
        // Then delete manually
        client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());

        // Verify it's gone - HeadBucket should return 404
        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build())
        );
    }

    @Test
    @DisplayName("删除Bucket后HeadBucket返回404")
    void testDeleteBucket_ThenHeadReturns404() {
        var bucketName = bucketFixture.createBucket("del-head");
        var client = bucketFixture.getClient();

        client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build())
        );
    }

    @Test
    @DisplayName("删除非空Bucket - 返回409 BucketNotEmpty")
    void testDeleteNonEmptyBucket_Fails() {
        var bucketName = bucketFixture.createBucket("del-nonempty");
        var client = bucketFixture.getClient();

        // Put an object to make the bucket non-empty
        ObjectHelper.putObject(client, bucketName, ObjectFixture.randomKey(), ObjectFixture.randomBytes(ObjectFixture.SIZE_1KB));

        AssertS3.assertThrowsS3Exception(409, "BucketNotEmpty", () ->
                client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
        );
    }

    @Test
    @DisplayName("删除不存在的Bucket - 返回404 NoSuchBucket")
    void testDeleteNonExistentBucket() {
        var client = bucketFixture.getClient();
        var fakeBucket = "s3test-del-noexist-" + System.nanoTime();

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                client.deleteBucket(DeleteBucketRequest.builder().bucket(fakeBucket).build())
        );
    }

    @Test
    @Disabled("需要第二个无权限账号才能测试")
    @DisplayName("删除Bucket - 无权限返回403")
    void testDeleteBucket_NoPermission() {
        // Requires a second account without delete permission on this bucket.
        // Skipped by default; enable when multi-account test environment is available.
        var bucketName = bucketFixture.createBucket("del-noperm");
        S3Client noPermClient = null; // would need a restricted-credentials client

        AssertS3.assertThrowsS3Exception(403, "AccessDenied", () ->
                noPermClient.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
        );
    }

    @Test
    @DisplayName("删除含未完成Multipart上传的Bucket - 返回409 BucketNotEmpty")
    void testDeleteBucket_WithIncompleteMultipart() {
        var bucketName = bucketFixture.createBucket("del-multipart");
        var client = bucketFixture.getClient();

        // Initiate a multipart upload but don't complete it
        client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(ObjectFixture.randomKey())
                        .build()
        );

        AssertS3.assertThrowsS3Exception(409, "BucketNotEmpty", () ->
                client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
        );
    }
}
