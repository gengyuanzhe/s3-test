package com.s3test.bucket;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.services.s3.model.Bucket;

import com.s3test.fixture.BucketFixture;

@DisplayName("ListBuckets 操作测试")
class ListBucketsTest {

    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("ListBuckets - 包含已创建的多个Bucket")
    void testListBuckets_ContainsCreatedBuckets() {
        var bucket1 = bucketFixture.createBucket("list-a");
        var bucket2 = bucketFixture.createBucket("list-b");
        var bucket3 = bucketFixture.createBucket("list-c");

        var response = bucketFixture.getClient().listBuckets();
        List<String> names = response.buckets().stream().map(Bucket::name).toList();

        assertTrue(names.contains(bucket1), "ListBuckets should contain " + bucket1);
        assertTrue(names.contains(bucket2), "ListBuckets should contain " + bucket2);
        assertTrue(names.contains(bucket3), "ListBuckets should contain " + bucket3);
    }

    @Test
    @Disabled("空账号场景难以在共享测试环境中模拟")
    @DisplayName("ListBuckets - 空账号返回空列表")
    void testListBuckets_EmptyAccount() {
        // This test would require a brand-new account with no buckets.
        // Not realistic in a shared test environment; skipped by default.
    }

    @Test
    @DisplayName("ListBuckets - 新创建的Bucket立即可见")
    void testListBuckets_NewBucketImmediatelyVisible() {
        var bucketName = bucketFixture.createBucket("list-imm");

        var response = bucketFixture.getClient().listBuckets();
        List<String> names = response.buckets().stream().map(Bucket::name).toList();

        assertTrue(names.contains(bucketName),
                "Newly created bucket should be immediately visible in ListBuckets: " + bucketName);
    }

    @Test
    @Disabled("单账号环境无法验证只看到自己的Bucket")
    @DisplayName("ListBuckets - 只列出自己拥有的Bucket")
    void testListBuckets_OnlyOwnBuckets() {
        // This test requires a multi-account setup to verify isolation.
        // Skipped by default; enable when multi-account test environment is available.
    }
}
