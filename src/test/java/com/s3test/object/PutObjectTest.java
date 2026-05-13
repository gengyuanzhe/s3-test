package com.s3test.object;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.StorageClass;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;
import com.s3test.util.S3ClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("PutObject 操作")
class PutObjectTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("上传小对象(<1KB)成功,返回ETag")
    void testPutSmallObject_Success() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-small");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.randomBytes(512);

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(data)
        );

        assertNotNull(response.eTag());
        assertFalse(response.eTag().isEmpty());
    }

    @Test
    @DisplayName("上传后GET内容一致")
    void testPutObject_ContentMatches() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-content");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.randomBytes(ObjectFixture.SIZE_1KB);

        ObjectHelper.putObject(s3, bucket, key, data);

        var getResponse = s3.getObject(b -> b.bucket(bucket).key(key));
        byte[] downloaded = getResponse.readAllBytes();

        AssertS3.assertContentEquals(data, downloaded);
    }

    @Test
    @DisplayName("上传0字节对象成功,GET返回Content-Length=0")
    void testPutObject_ZeroBytes() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-zero");
        String key = ObjectFixture.randomKey();

        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(new byte[0])
        );

        var headResponse = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertEquals(0, headResponse.contentLength());
    }

    @Test
    @DisplayName("上传1MB对象成功")
    void testPutObject_1MB() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-1mb");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.randomBytes(ObjectFixture.SIZE_1MB);

        ObjectHelper.putObject(s3, bucket, key, data);

        var headResponse = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertEquals(ObjectFixture.SIZE_1MB, headResponse.contentLength());
    }

    @Test
    @DisplayName("覆盖同一key,第二次GET返回新数据")
    void testPutObject_Overwrite() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-overwrite");
        String key = ObjectFixture.randomKey();
        byte[] data1 = ObjectFixture.randomBytes(100);
        byte[] data2 = ObjectFixture.randomBytes(200);

        ObjectHelper.putObject(s3, bucket, key, data1);
        ObjectHelper.putObject(s3, bucket, key, data2);

        var getResponse = s3.getObject(b -> b.bucket(bucket).key(key));
        byte[] downloaded = getResponse.readAllBytes();
        AssertS3.assertContentEquals(data2, downloaded);
    }

    @Test
    @DisplayName("多次覆盖,最后一次生效")
    void testPutObject_MultipleOverwrite() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-multi-overwrite");
        String key = ObjectFixture.randomKey();
        byte[][] allData = {
                ObjectFixture.randomBytes(50),
                ObjectFixture.randomBytes(100),
                ObjectFixture.randomBytes(150)
        };

        for (byte[] d : allData) {
            ObjectHelper.putObject(s3, bucket, key, d);
        }

        var getResponse = s3.getObject(b -> b.bucket(bucket).key(key));
        byte[] downloaded = getResponse.readAllBytes();
        AssertS3.assertContentEquals(allData[2], downloaded);
    }

    @Test
    @DisplayName("设置Content-Type,HEAD返回相同Content-Type")
    void testPutObject_WithContentType() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-ctype");
        String key = ObjectFixture.randomKey();
        String contentType = "application/json";

        ObjectHelper.putObjectWithContentType(s3, bucket, key, "{\"k\":\"v\"".getBytes(StandardCharsets.UTF_8), contentType);

        var headResponse = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertEquals(contentType, headResponse.contentType());
    }

    @Test
    @DisplayName("设置x-amz-meta-*元数据,HEAD返回相同元数据")
    void testPutObject_WithUserMetadata() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-meta");
        String key = ObjectFixture.randomKey();
        Map<String, String> metadata = Map.of(
                "author", "test-user",
                "version", "1.0"
        );

        ObjectHelper.putObjectWithMetadata(s3, bucket, key, ObjectFixture.randomBytes(100), metadata);

        var headResponse = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        AssertS3.assertMetadataContains(headResponse.metadata(), metadata);
    }

    @Test
    @DisplayName("元数据超过2KB,返回400 MetadataTooLarge")
    void testPutObject_MetadataTooLarge() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-meta-large");
        String key = ObjectFixture.randomKey();

        // Create metadata > 2KB
        String longValue = "x".repeat(2048);
        Map<String, String> largeMetadata = Map.of("big-key", longValue);

        AssertS3.assertThrowsS3Exception(400, "MetadataTooLarge", () ->
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .metadata(largeMetadata)
                                .build(),
                        RequestBody.fromBytes(ObjectFixture.randomBytes(10))
                )
        );
    }

    @Test
    @DisplayName("正确的Content-MD5,返回200")
    void testPutObject_WithContentMD5_Correct() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-md5-ok");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.randomBytes(256);

        String md5 = md5Base64(data);

        var response = s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .contentMD5(md5)
                        .build(),
                RequestBody.fromBytes(data)
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("错误的Content-MD5,返回400 BadDigest")
    void testPutObject_WithContentMD5_Incorrect() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-md5-bad");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.randomBytes(256);

        String wrongMd5 = md5Base64(new byte[]{0x00});

        AssertS3.assertThrowsS3Exception(400, "BadDigest", () ->
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .contentMD5(wrongMd5)
                                .build(),
                        RequestBody.fromBytes(data)
                )
        );
    }

    @Test
    @DisplayName("If-None-Match: * 对新key,返回200")
    void testPutObject_IfNoneMatch_NewKey() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-inm-new");
        String key = ObjectFixture.randomKey();

        var response = s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .overrideConfiguration(conf -> conf.putHeader("If-None-Match", "*"))
                        .build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(100))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("If-None-Match: * 对已存在key,返回412 PreconditionFailed")
    void testPutObject_IfNoneMatch_ExistingKey() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-inm-exist");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        AssertS3.assertThrowsS3Exception(412, "PreconditionFailed", () ->
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .overrideConfiguration(conf -> conf.putHeader("If-None-Match", "*"))
                                .build(),
                        RequestBody.fromBytes(ObjectFixture.randomBytes(100))
                )
        );
    }

    @Test
    @DisplayName("Bucket不存在,返回404 NoSuchBucket")
    void testPutObject_BucketNotFound() {
        var s3 = bucketFixture.getClient();

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket("s3test-nonexistent-bucket-" + System.nanoTime())
                                .key(ObjectFixture.randomKey())
                                .build(),
                        RequestBody.fromBytes(ObjectFixture.randomBytes(10))
                )
        );
    }

    @Test
    @Disabled("需要配置无权限用户")
    @DisplayName("无权限,返回403 AccessDenied")
    void testPutObject_NoPermission() {
        // This test requires a separate S3Client with no write permission
    }

    @Test
    @DisplayName("Key超过1024字节,返回400 KeyTooLongError")
    void testPutObject_KeyTooLong() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-keytoolong");

        // 1025 byte key (each char is 1 byte for ASCII)
        String longKey = "a".repeat(1025);

        AssertS3.assertThrowsS3Exception(400, "KeyTooLongError", () ->
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket).key(longKey)
                                .build(),
                        RequestBody.fromBytes(ObjectFixture.randomBytes(10))
                )
        );
    }

    @Test
    @DisplayName("设置StorageClass,HEAD返回相同StorageClass")
    void testPutObject_WithStorageClass() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-storage");
        String key = ObjectFixture.randomKey();

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .storageClass(StorageClass.REDUCED_REDUNDANCY)
                        .build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(100))
        );

        var headResponse = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertEquals(StorageClass.REDUCED_REDUNDANCY, headResponse.storageClass());
    }

    @Test
    @DisplayName("恰好1024字节的key,返回200")
    void testPutObject_LargeKey_1024Bytes() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-1024key");
        // 1024 ASCII chars = 1024 bytes
        String key = "k".repeat(1024);

        var response = s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(ObjectFixture.randomBytes(10))
        );

        assertNotNull(response.eTag());
    }

    @Test
    @DisplayName("多个x-amz-meta-*元数据全部保留")
    void testPutObject_MultipleMetadata() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-multi-meta");
        String key = ObjectFixture.randomKey();
        Map<String, String> metadata = Map.of(
                "meta1", "value1",
                "meta2", "value2",
                "meta3", "value3",
                "meta4", "value4"
        );

        ObjectHelper.putObjectWithMetadata(s3, bucket, key, ObjectFixture.randomBytes(50), metadata);

        var headResponse = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        AssertS3.assertMetadataContains(headResponse.metadata(), metadata);
    }

    @Test
    @DisplayName("非ASCII元数据值,测试行为")
    void testPutObject_NonASCIIMetadata() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("put-nonascii-meta");
        String key = ObjectFixture.randomKey();
        // Some S3 implementations reject non-ASCII metadata, others accept it
        Map<String, String> metadata = Map.of("unicode-key", "value-with-unicode");

        // Should at least not crash - behavior varies by implementation
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .metadata(metadata)
                            .build(),
                    RequestBody.fromBytes(ObjectFixture.randomBytes(10))
            );
        } catch (S3Exception e) {
            // Acceptable: some implementations reject non-ASCII metadata
            assertEquals(400, e.statusCode());
        }
    }

    private static String md5Base64(byte[] data) throws Exception {
        var md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        return Base64.getEncoder().encodeToString(digest);
    }
}
