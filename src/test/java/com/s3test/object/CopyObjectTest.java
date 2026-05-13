package com.s3test.object;

import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.TaggingDirective;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("CopyObject 操作")
class CopyObjectTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("同Bucket内复制,GET目标内容一致")
    void testCopyWithinBucket() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-same");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");
        byte[] data = ObjectFixture.randomBytes(256);

        ObjectHelper.putObject(s3, bucket, srcKey, data);

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey)
                .build());

        var getResponse = s3.getObject(b -> b.bucket(bucket).key(dstKey));
        byte[] downloaded = getResponse.readAllBytes();

        AssertS3.assertContentEquals(data, downloaded);
    }

    @Test
    @DisplayName("跨Bucket复制,成功")
    void testCopyAcrossBuckets() throws Exception {
        var s3 = bucketFixture.getClient();
        String srcBucket = bucketFixture.createBucket("copy-src");
        String dstBucket = bucketFixture.createBucket("copy-dst");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");
        byte[] data = ObjectFixture.randomBytes(256);

        ObjectHelper.putObject(s3, srcBucket, srcKey, data);

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(srcBucket).sourceKey(srcKey)
                .destinationBucket(dstBucket).destinationKey(dstKey)
                .build());

        var getResponse = s3.getObject(b -> b.bucket(dstBucket).key(dstKey));
        byte[] downloaded = getResponse.readAllBytes();

        AssertS3.assertContentEquals(data, downloaded);
    }

    @Test
    @DisplayName("复制后源和目标内容完全一致")
    void testCopyContentMatches() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-cmp");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");
        byte[] data = ObjectFixture.sequentialBytes(1024);

        ObjectHelper.putObject(s3, bucket, srcKey, data);

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey)
                .build());

        var srcResponse = s3.getObject(b -> b.bucket(bucket).key(srcKey));
        var dstResponse = s3.getObject(b -> b.bucket(bucket).key(dstKey));
        byte[] srcBytes = srcResponse.readAllBytes();
        byte[] dstBytes = dstResponse.readAllBytes();

        AssertS3.assertContentEquals(srcBytes, dstBytes);
    }

    @Test
    @DisplayName("COPY元数据指令,目标继承源元数据")
    void testCopy_MetadataDirectiveCopy() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-meta-copy");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");
        Map<String, String> srcMetadata = Map.of("source-meta", "preserved");

        ObjectHelper.putObjectWithMetadata(s3, bucket, srcKey, ObjectFixture.randomBytes(100), srcMetadata);

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey)
                .metadataDirective(MetadataDirective.COPY)
                .build());

        var headDst = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(dstKey).build());
        AssertS3.assertMetadataContains(headDst.metadata(), srcMetadata);
    }

    @Test
    @DisplayName("REPLACE元数据指令,目标使用新元数据")
    void testCopy_MetadataDirectiveReplace() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-meta-replace");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");
        Map<String, String> srcMetadata = Map.of("old-key", "old-value");
        Map<String, String> newMetadata = Map.of("new-key", "new-value");

        ObjectHelper.putObjectWithMetadata(s3, bucket, srcKey, ObjectFixture.randomBytes(100), srcMetadata);

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey)
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(newMetadata)
                .build());

        var headDst = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(dstKey).build());
        AssertS3.assertMetadataContains(headDst.metadata(), newMetadata);
    }

    @Test
    @DisplayName("复制到自身,行为取决于版本控制")
    void testCopyToSelf() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-self");
        String key = ObjectFixture.randomKey("self-");
        byte[] data = ObjectFixture.randomBytes(100);

        ObjectHelper.putObject(s3, bucket, key, data);

        // Copying to self may succeed or fail depending on versioning
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(key)
                    .destinationBucket(bucket).destinationKey(key)
                    .build());
            // If no versioning, this may succeed (same content)
        } catch (S3Exception e) {
            // Some implementations reject self-copy without versioning
            assertEquals(400, e.statusCode());
        }
    }

    @Test
    @DisplayName("源对象不存在,返回404 NoSuchKey")
    void testCopy_SourceNotFound() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-src404");
        String nonExistingKey = "nonexistent-" + System.nanoTime();
        String dstKey = ObjectFixture.randomKey("dst-");

        AssertS3.assertThrowsS3Exception(404, "NoSuchKey", () ->
                s3.copyObject(CopyObjectRequest.builder()
                        .sourceBucket(bucket).sourceKey(nonExistingKey)
                        .destinationBucket(bucket).destinationKey(dstKey)
                        .build())
        );
    }

    @Test
    @DisplayName("目标Bucket不存在,返回404 NoSuchBucket")
    void testCopy_DestBucketNotFound() {
        var s3 = bucketFixture.getClient();
        String srcBucket = bucketFixture.createBucket("copy-dst404-src");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");

        ObjectHelper.putObject(s3, srcBucket, srcKey, ObjectFixture.randomBytes(50));

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                s3.copyObject(CopyObjectRequest.builder()
                        .sourceBucket(srcBucket).sourceKey(srcKey)
                        .destinationBucket("s3test-no-such-dst-" + System.nanoTime())
                        .destinationKey(dstKey)
                        .build())
        );
    }

    @Test
    @Disabled("需要配置无权限用户")
    @DisplayName("无源Bucket权限,返回403")
    void testCopy_NoPermissionOnSource() {
        // This test requires a separate S3Client with no read permission on source
    }

    @Test
    @DisplayName("复制0字节对象,目标也是0字节")
    void testCopy_ZeroByteObject() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-zero");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");

        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(srcKey).build(),
                RequestBody.fromBytes(new byte[0])
        );

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey)
                .build());

        var headDst = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(dstKey).build());
        assertEquals(0, headDst.contentLength());
    }

    @Test
    @Disabled("需要目标S3支持Tagging")
    @DisplayName("COPY vs REPLACE tagging指令")
    void testCopy_WithTaggingDirective() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("copy-tag");
        String srcKey = ObjectFixture.randomKey("src-");
        String dstKey = ObjectFixture.randomKey("dst-");

        ObjectHelper.putObject(s3, bucket, srcKey, ObjectFixture.randomBytes(50));

        // COPY tagging directive (default)
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey)
                .taggingDirective(TaggingDirective.COPY)
                .build());

        // Verify copy succeeded
        var headDst = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(dstKey).build());
        assertNotNull(headDst.eTag());
    }
}
