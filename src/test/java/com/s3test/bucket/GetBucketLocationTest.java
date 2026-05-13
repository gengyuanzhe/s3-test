package com.s3test.bucket;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;

import com.s3test.fixture.BucketFixture;
import com.s3test.util.AssertS3;

@DisplayName("GetBucketLocation 操作测试")
class GetBucketLocationTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("GetBucketLocation - 已存在的Bucket返回200")
    void testGetBucketLocation_Success() {
        var bucketName = bucketFixture.createBucket("location-ok");
        var client = bucketFixture.getClient();

        var response = client.getBucketLocation(
                GetBucketLocationRequest.builder().bucket(bucketName).build()
        );

        assertNotNull(response.locationConstraint());
    }

    @Test
    @DisplayName("GetBucketLocation - 不存在的Bucket返回404")
    void testGetBucketLocation_NonExistentBucket() {
        var client = bucketFixture.getClient();
        var fakeBucket = "s3test-loc-noexist-" + System.nanoTime();

        AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () ->
                client.getBucketLocation(
                        GetBucketLocationRequest.builder().bucket(fakeBucket).build()
                )
        );
    }
}
