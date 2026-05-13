package com.s3test.object;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeleteObjects 批量删除")
class DeleteObjectsTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("批量删除3个已存在对象,全部Deleted")
    void testBatchDelete_AllExist() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("batchdel-all");

        List<ObjectIdentifier> toDelete = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String key = ObjectFixture.randomKey("batch-" + i + "-");
            ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(50));
            toDelete.add(ObjectIdentifier.builder().key(key).build());
        }

        var response = s3.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(toDelete).quiet(false).build())
                        .build()
        );

        assertEquals(3, response.deleted().size());
    }

    @Test
    @DisplayName("批量删除混合存在/不存在的对象,S3幂等处理")
    void testBatchDelete_MixedExistNotExist() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("batchdel-mix");

        String existingKey = ObjectFixture.randomKey("exist-");
        ObjectHelper.putObject(s3, bucket, existingKey, ObjectFixture.randomBytes(50));

        String nonExistingKey = "nonexistent-" + System.nanoTime();

        List<ObjectIdentifier> toDelete = List.of(
                ObjectIdentifier.builder().key(existingKey).build(),
                ObjectIdentifier.builder().key(nonExistingKey).build()
        );

        var response = s3.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(toDelete).quiet(false).build())
                        .build()
        );

        // S3 treats delete as idempotent: both should appear in Deleted
        assertEquals(2, response.deleted().size());
    }

    @Test
    @DisplayName("批量创建1000个对象并批量删除,全部成功")
    void testBatchDelete_1000Objects() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("batchdel-1k");

        List<ObjectIdentifier> toDelete = new ArrayList<>();
        byte[] data = ObjectFixture.randomBytes(10);
        for (int i = 0; i < 1000; i++) {
            String key = ObjectFixture.randomKey("k" + i + "-");
            ObjectHelper.putObject(s3, bucket, key, data);
            toDelete.add(ObjectIdentifier.builder().key(key).build());
        }

        var response = s3.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(toDelete).quiet(true).build())
                        .build()
        );

        // Verify all deleted by listing
        var listResponse = s3.listObjectsV2(b -> b.bucket(bucket));
        assertTrue(listResponse.contents().isEmpty(), "Bucket should be empty after batch delete");
    }

    @Test
    @DisplayName("批量删除超过1000个key,返回错误")
    void testBatchDelete_Exceeds1000() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("batchdel-exc");

        List<ObjectIdentifier> toDelete = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            toDelete.add(ObjectIdentifier.builder().key("key-" + i).build());
        }

        AssertS3.assertThrowsS3Exception(400, "MalformedXML", () ->
                s3.deleteObjects(
                        DeleteObjectsRequest.builder()
                                .bucket(bucket)
                                .delete(Delete.builder().objects(toDelete).build())
                                .build()
                )
        );
    }

    @Test
    @DisplayName("Quiet模式,响应中不包含Deleted列表")
    void testBatchDelete_QuietMode() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("batchdel-quiet");

        String key = ObjectFixture.randomKey("quiet-");
        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(50));

        List<ObjectIdentifier> toDelete = List.of(
                ObjectIdentifier.builder().key(key).build()
        );

        var response = s3.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(toDelete).quiet(true).build())
                        .build()
        );

        // In quiet mode, deleted list should be empty
        assertTrue(response.deleted().isEmpty(), "Quiet mode should not return deleted list");
    }

    @Test
    @DisplayName("Verbose模式(quiet=false),响应包含Deleted列表")
    void testBatchDelete_VerboseMode() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("batchdel-verbose");

        String key = ObjectFixture.randomKey("verbose-");
        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(50));

        List<ObjectIdentifier> toDelete = List.of(
                ObjectIdentifier.builder().key(key).build()
        );

        var response = s3.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(toDelete).quiet(false).build())
                        .build()
        );

        assertEquals(1, response.deleted().size());
        assertEquals(key, response.deleted().get(0).key());
    }
}
