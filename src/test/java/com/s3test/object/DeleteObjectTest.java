package com.s3test.object;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.fixture.ObjectHelper;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeleteObject 操作")
class DeleteObjectTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("删除已存在的对象,返回204")
    void testDeleteExistingObject() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("del-existing");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));

        var response = s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

        assertEquals(204, response.sdkHttpResponse().statusCode());
    }

    @Test
    @DisplayName("删除后GET返回404 NoSuchKey")
    void testDeleteThenGetReturns404() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("del-then-get");
        String key = ObjectFixture.randomKey();

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(100));
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

        AssertS3.assertThrowsS3Exception(404, "NoSuchKey", () ->
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
        );
    }

    @Test
    @DisplayName("删除不存在的对象,返回204(幂等)")
    void testDeleteNonExistentObject() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("del-nonexist");

        var response = s3.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key("nonexistent-" + System.nanoTime())
                        .build()
        );

        assertEquals(204, response.sdkHttpResponse().statusCode());
    }

    @Test
    @Disabled("需要配置无权限用户")
    @DisplayName("无权限删除,返回403")
    void testDeleteNoPermission() {
        // This test requires a separate S3Client with no delete permission
    }

    @Test
    @DisplayName("删除后ListObjectsV2中不包含该key")
    void testDeleteNotInList() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("del-list");
        String key = ObjectFixture.randomKey("delete-me-");

        ObjectHelper.putObject(s3, bucket, key, ObjectFixture.randomBytes(50));
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

        var listResponse = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
        boolean keyPresent = listResponse.contents().stream()
                .anyMatch(obj -> obj.key().equals(key));

        assertTrue(!keyPresent, "Deleted key should not appear in list results");
    }
}
