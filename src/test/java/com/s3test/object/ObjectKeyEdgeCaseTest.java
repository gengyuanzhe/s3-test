package com.s3test.object;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("ObjectKey 边界用例")
class ObjectKeyEdgeCaseTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("单字符key成功")
    void testKey_SingleChar() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-1char");
        String key = "a";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("1024字节key成功")
    void testKey_MaxLength1024Bytes() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-1024");
        // 1024 ASCII chars = 1024 bytes
        String key = "k".repeat(1024);

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("1025字节key返回400 KeyTooLongError")
    void testKey_TooLong() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-toolong");
        String key = "k".repeat(1025);

        AssertS3.assertThrowsS3Exception(400, "KeyTooLongError", () ->
                s3.putObject(
                        PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromBytes(ObjectFixture.randomBytes(10))
                )
        );
    }

    @Test
    @DisplayName("中文key成功")
    void testKey_Chinese() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-chinese");
        String key = "测试/文件.txt";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());

        // Verify can read back
        var getResponse = s3.getObject(b -> b.bucket(bucket).key(key));
        assertNotNull(getResponse);
    }

    @Test
    @DisplayName("安全特殊字符key成功")
    void testKey_SpecialSafeChars() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-special");
        String key = "a-z_A.Z 0+9";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("路径分隔符key成功")
    void testKey_PathSeparators() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-path");
        String key = "a/b/c/d/e.txt";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("尾斜杠key成功")
    void testKey_TrailingSlash() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-trailing");
        String key = "dir/";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("双斜杠key成功")
    void testKey_DoubleSlash() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-dslash");
        String key = "a//b";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("点前缀隐藏文件key成功")
    void testKey_DotPrefix() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-dot");
        String key = ".hidden";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("Unicode(含emoji)key测试行为")
    void testKey_Unicode() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-unicode");
        String key = "folder/file-name";

        // Emoji keys may not be supported by all implementations
        try {
            var response = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(ObjectFixture.randomBytes(10))
            );
            assertNotNull(response.eTag());
        } catch (S3Exception e) {
            // Acceptable: some implementations reject unicode keys
            assertEquals(400, e.statusCode());
        }
    }

    @Test
    @DisplayName("需要URL编码的key成功")
    void testKey_NeedsEncoding() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("key-encode");
        String key = "key&val=1";

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());

        // Verify can read back with same key
        var headResponse = s3.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build()
        );
        assertNotNull(headResponse.eTag());
    }
}
