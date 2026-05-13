package com.s3test.bucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import com.s3test.config.S3TestConfig;
import com.s3test.fixture.BucketFixture;
import com.s3test.util.AssertS3;

@DisplayName("CreateBucket 操作测试")
class CreateBucketTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("正常创建Bucket - HeadBucket返回200")
    void testCreateBucket_Success() {
        var bucketName = bucketFixture.createBucket("create-success");

        var response = bucketFixture.getClient()
                .headBucket(HeadBucketRequest.builder().bucket(bucketName).build());

        assertEquals(200, response.sdkHttpResponse().statusCode());
    }

    @Test
    @DisplayName("创建Bucket - 最小名称长度3字符")
    void testCreateBucket_MinNameLength() {
        var bucketName = "ab3";
        bucketFixture.createBucketWithName(bucketName);

        var response = bucketFixture.getClient()
                .headBucket(HeadBucketRequest.builder().bucket(bucketName).build());

        assertEquals(200, response.sdkHttpResponse().statusCode());
    }

    @Test
    @DisplayName("创建Bucket - 最大名称长度63字符")
    void testCreateBucket_MaxNameLength() {
        // 63 chars: all valid lowercase + digits + hyphens
        var bucketName = "a" + "-bcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxy" + "z";
        assertEquals(63, bucketName.length());
        bucketFixture.createBucketWithName(bucketName);

        var response = bucketFixture.getClient()
                .headBucket(HeadBucketRequest.builder().bucket(bucketName).build());

        assertEquals(200, response.sdkHttpResponse().statusCode());
    }

    @Test
    @DisplayName("重复创建同名的Bucket - 同一Owner返回409")
    void testCreateBucket_DuplicateSameOwner() {
        var bucketName = bucketFixture.createBucket("dup-owner");

        AssertS3.assertThrowsS3Exception(409, "BucketAlreadyOwnedByYou", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket(bucketName).build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - 包含大写字母返回400")
    void testCreateBucket_InvalidName_Uppercase() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("TestBucket").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - 包含特殊字符返回400")
    void testCreateBucket_InvalidName_SpecialChars() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("test!bucket").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - 以连字符开头返回400")
    void testCreateBucket_InvalidName_StartsWithHyphen() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("-test").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - 以连字符结尾返回400")
    void testCreateBucket_InvalidName_EndsWithHyphen() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("test-").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - 空字符串返回400")
    void testCreateBucket_InvalidName_Empty() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - 名称过短(2字符)返回400")
    void testCreateBucket_InvalidName_TooShort() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("ab").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - 名称过长(64字符)返回400")
    void testCreateBucket_InvalidName_TooLong() {
        // 64 chars - just over the 63-char limit
        var bucketName = "a" + "-bcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123" + "z";
        assertEquals(64, bucketName.length());

        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket(bucketName).build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - IP地址格式返回400")
    void testCreateBucket_InvalidName_IpFormat() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("192.168.1.1").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - xn--前缀返回400")
    void testCreateBucket_InvalidName_PrefixXn() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("xn--test").build()
                )
        );
    }

    @Test
    @DisplayName("无效Bucket名 - sthree-前缀返回400")
    void testCreateBucket_InvalidName_PrefixSthree() {
        AssertS3.assertThrowsS3Exception(400, "InvalidBucketName", () ->
                bucketFixture.getClient().createBucket(
                        CreateBucketRequest.builder().bucket("sthree-test").build()
                )
        );
    }

    @Test
    @DisplayName("创建Bucket - 指定LocationConstraint并验证")
    void testCreateBucket_WithLocationConstraint() {
        var bucketName = bucketFixture.createBucket("location");
        var client = bucketFixture.getClient();

        var locationResponse = client.getBucketLocation(
                GetBucketLocationRequest.builder().bucket(bucketName).build()
        );

        assertNotNull(locationResponse.locationConstraint());
    }

    @Test
    @DisplayName("创建Bucket后立即可在ListBuckets中看到")
    void testCreateBucket_VerifyListedImmediately() {
        var bucketName = bucketFixture.createBucket("list-imm");
        var client = bucketFixture.getClient();

        ListBucketsResponse listResponse = client.listBuckets();

        boolean found = listResponse.buckets().stream()
                .anyMatch(b -> b.name().equals(bucketName));
        assertTrue(found, "Newly created bucket should appear in ListBuckets: " + bucketName);
    }

    @Test
    @Disabled("需要第二个无权限账号才能测试")
    @DisplayName("创建Bucket - 无权限返回403")
    void testCreateBucket_NoPermission() {
        // This test requires a second account without bucket creation permission.
        // Skipped by default; enable when multi-account test environment is available.
        var bucketName = S3TestConfig.generateBucketName("noperm");
        S3Client noPermClient = null; // would need a restricted-credentials client
        AssertS3.assertThrowsS3Exception(403, "AccessDenied", () ->
                noPermClient.createBucket(
                        CreateBucketRequest.builder().bucket(bucketName).build()
                )
        );
    }
}
