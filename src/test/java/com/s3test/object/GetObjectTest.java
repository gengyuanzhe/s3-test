package com.s3test.object;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GetObject 操作")
class GetObjectTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("GET完整对象,内容一致")
    void testGetFullObject() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-full");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.sequentialBytes(ObjectFixture.SIZE_1KB);

        ObjectHelper.putObject(s3, bucket, key, data);

        var responseStream = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        byte[] downloaded = responseStream.readAllBytes();

        AssertS3.assertContentEquals(data, downloaded);
    }

    @Test
    @DisplayName("GET零字节对象,返回200,Content-Length=0")
    void testGetZeroByteObject() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-zero");
        String key = ObjectFixture.randomKey();

        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(new byte[0])
        );

        var responseStream = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        byte[] downloaded = responseStream.readAllBytes();
        var response = responseStream.response();

        assertEquals(0, response.contentLength());
        assertEquals(0, downloaded.length);
    }

    @Test
    @DisplayName("GET返回PUT时设置的所有元数据")
    void testGetObject_AllMetadata() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-meta");
        String key = ObjectFixture.randomKey();
        Map<String, String> metadata = Map.of("foo", "bar", "baz", "qux");

        ObjectHelper.putObjectWithMetadata(s3, bucket, key, ObjectFixture.randomBytes(100), metadata);

        var responseStream = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        var response = responseStream.response();

        AssertS3.assertMetadataContains(response.metadata(), metadata);
    }

    @Test
    @DisplayName("Range请求前10字节,返回206,内容正确")
    void testGetRange_First10Bytes() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-range-first");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.sequentialBytes(1024);

        ObjectHelper.putObject(s3, bucket, key, data);

        var responseStream = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-9").build()
        );
        byte[] downloaded = responseStream.readAllBytes();
        var response = responseStream.response();

        assertEquals(206, response.sdkHttpResponse().statusCode());
        assertArrayEquals(Arrays.copyOfRange(data, 0, 10), downloaded);
    }

    @Test
    @DisplayName("Range请求中间字节,返回206,内容正确")
    void testGetRange_MiddleBytes() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-range-mid");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.sequentialBytes(500);

        ObjectHelper.putObject(s3, bucket, key, data);

        var responseStream = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=100-199").build()
        );
        byte[] downloaded = responseStream.readAllBytes();
        var response = responseStream.response();

        assertEquals(206, response.sdkHttpResponse().statusCode());
        assertArrayEquals(Arrays.copyOfRange(data, 100, 200), downloaded);
    }

    @Test
    @DisplayName("Range请求最后100字节,返回206,内容正确")
    void testGetRange_Last100Bytes() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-range-last");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.sequentialBytes(500);

        ObjectHelper.putObject(s3, bucket, key, data);

        var responseStream = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=-100").build()
        );
        byte[] downloaded = responseStream.readAllBytes();
        var response = responseStream.response();

        assertEquals(206, response.sdkHttpResponse().statusCode());
        assertArrayEquals(Arrays.copyOfRange(data, 400, 500), downloaded);
    }

    @Test
    @DisplayName("Range从起始到末尾,返回全部内容")
    void testGetRange_FromStartToEnd() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-range-all");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.sequentialBytes(256);

        ObjectHelper.putObject(s3, bucket, key, data);

        var responseStream = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-").build()
        );
        byte[] downloaded = responseStream.readAllBytes();

        AssertS3.assertContentEquals(data, downloaded);
    }

    @Test
    @DisplayName("Range超出对象大小,返回416 InvalidRange")
    void testGetRange_OutOfBounds() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-range-oob");
        String key = ObjectFixture.randomKey();
        byte[] data = ObjectFixture.randomBytes(100);

        ObjectHelper.putObject(s3, bucket, key, data);

        AssertS3.assertThrowsS3Exception(416, "InvalidRange", () ->
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .range("bytes=100000-200000")
                                .build()
                )
        );
    }

    @Test
    @DisplayName("If-Match匹配正确ETag,返回200")
    void testGet_IfMatch_Matches() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-ifmatch-ok");
        String key = ObjectFixture.randomKey();

        var putResponse = ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));
        String etag = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).eTag();

        var responseStream = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).ifMatch(etag).build()
        );
        var response = responseStream.response();

        assertEquals(200, response.sdkHttpResponse().statusCode());
    }

    @Test
    @DisplayName("If-Match不匹配ETag,返回412 PreconditionFailed")
    void testGet_IfMatch_NotMatches() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-ifmatch-fail");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        AssertS3.assertThrowsS3Exception(412, "PreconditionFailed", () ->
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .ifMatch("\"wrong-etag\"")
                                .build()
                )
        );
    }

    @Test
    @DisplayName("If-None-Match匹配ETag,返回304 NotModified")
    void testGet_IfNoneMatch_Matches() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-inm-match");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));
        String etag = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).eTag();

        AssertS3.assertThrowsS3Exception(304, "NotModified", () ->
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .ifNoneMatch(etag)
                                .build()
                )
        );
    }

    @Test
    @DisplayName("If-Modified-Since未来时间,返回304")
    void testGet_IfModifiedSince_NotModified() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-ims-future");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        // Future date: object was not modified since this future time
        Instant futureDate = Instant.now().plusSeconds(3600);

        AssertS3.assertThrowsS3Exception(304, "NotModified", () ->
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .ifModifiedSince(futureDate)
                                .build()
                )
        );
    }

    @Test
    @DisplayName("If-Unmodified-Since过去时间(已修改),返回412")
    void testGet_IfUnmodifiedSince_Modified() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-ius-modified");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        // Past date: object was modified after this date
        Instant pastDate = Instant.now().minusSeconds(3600);

        AssertS3.assertThrowsS3Exception(412, "PreconditionFailed", () ->
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .ifUnmodifiedSince(pastDate)
                                .build()
                )
        );
    }

    @Test
    @DisplayName("response-content-type覆盖响应Content-Type")
    void testGet_ResponseContentTypeOverride() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-rct-override");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        var responseStream = s3.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .responseContentType("application/xml")
                        .build()
        );
        var response = responseStream.response();

        assertEquals("application/xml", response.contentType());
    }

    @Test
    @DisplayName("response-content-disposition覆盖响应Content-Disposition")
    void testGet_ResponseContentDisposition() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-rcd-override");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        String disposition = "attachment; filename=\"test.txt\"";
        var responseStream = s3.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .responseContentDisposition(disposition)
                        .build()
        );
        var response = responseStream.response();

        assertEquals(disposition, response.contentDisposition());
    }

    @Test
    @DisplayName("对象不存在,返回404 NoSuchKey")
    void testGet_ObjectNotFound() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("get-notfound");

        AssertS3.assertThrowsS3Exception(404, "NoSuchKey", () ->
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket).key("nonexistent-key-" + System.nanoTime())
                                .build()
                )
        );
    }

    @Test
    @DisplayName("Bucket不存在,返回404 NoSuchBucket")
    void testGet_BucketNotFound() {
        var s3 = bucketFixture.getClient();

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket("s3test-no-such-bucket-" + System.nanoTime())
                                .key("any-key")
                                .build()
                )
        );
    }

    @Test
    @Disabled("需要配置无权限用户")
    @DisplayName("无权限,返回403 AccessDenied")
    void testGet_NoPermission() {
        // This test requires a separate S3Client with no read permission
    }
}
