package com.s3test.object;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HeadObject 操作")
class HeadObjectTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("对象存在,返回200,验证ETag/Content-Length/LastModified")
    void testHeadObject_Exists() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("head-exists");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.randomBytes(256);

        ObjectHelper.putObject(s3, bucket, key, data);

        var response = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());

        assertNotNull(response.eTag());
        assertFalse(response.eTag().isEmpty());
        assertEquals(256, response.contentLength());
        assertNotNull(response.lastModified());
        assertTrue(response.lastModified().isBefore(Instant.now().plusSeconds(5)));
    }

    @Test
    @DisplayName("对象不存在,返回404 NoSuchKey")
    void testHeadObject_NotExists() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("head-notexists");

        AssertS3.assertThrowsS3Exception(404, "NoSuchKey", () ->
                s3.headObject(
                        HeadObjectRequest.builder()
                                .bucket(bucket)
                                .key("nonexistent-" + System.nanoTime())
                                .build()
                )
        );
    }

    @Test
    @DisplayName("HEAD返回所有元数据完整")
    void testHeadObject_MetadataIntact() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("head-meta");
        String key = ObjectFixture.randomKey();
        Map<String, String> metadata = Map.of(
                "author", "tester",
                "env", "production"
        );

        ObjectHelper.putObjectWithMetadata(s3, bucket, key, ObjectFixture.randomBytes(100), metadata);

        var response = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        AssertS3.assertMetadataContains(response.metadata(), metadata);
    }

    @Test
    @DisplayName("上传N字节,HEAD返回Content-Length==N")
    void testHeadObject_ContentLength() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("head-clen");
        String key = ObjectFixture.randomKey();
        int size = 12345;
        byte[] data = ObjectFixture.randomBytes(size);

        ObjectHelper.putObject(s3, bucket, key, data);

        var response = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertEquals(size, response.contentLength());
    }

    @Test
    @DisplayName("If-Match匹配ETag,返回200;不匹配返回412")
    void testHeadObject_IfMatch() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("head-ifmatch");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));
        String etag = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).eTag();

        // Matching ETag -> 200
        var response = s3.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).ifMatch(etag).build()
        );
        assertEquals(200, response.sdkHttpResponse().statusCode());

        // Non-matching ETag -> 412
        AssertS3.assertThrowsS3Exception(412, "PreconditionFailed", () ->
                s3.headObject(
                        HeadObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .ifMatch("\"wrong-etag\"")
                                .build()
                )
        );
    }

    @Test
    @DisplayName("If-None-Match匹配ETag,返回304;不匹配返回200")
    void testHeadObject_IfNoneMatch() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("head-ifnonematch");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));
        String etag = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).eTag();

        // Matching ETag -> 304
        AssertS3.assertThrowsS3Exception(304, "NotModified", () ->
                s3.headObject(
                        HeadObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .ifNoneMatch(etag)
                                .build()
                )
        );

        // Non-matching ETag -> 200
        var response = s3.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("\"wrong\"").build()
        );
        assertEquals(200, response.sdkHttpResponse().statusCode());
    }
}
