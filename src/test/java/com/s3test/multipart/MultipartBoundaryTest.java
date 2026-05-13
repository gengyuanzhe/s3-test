package com.s3test.multipart;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Multipart Upload Boundary Conditions")
class MultipartBoundaryTest {

    @RegisterExtension
    static final BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("Non-last part exactly 5MB succeeds")
    void testPartExactly5MB() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-5mb");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] exactly5MB = ObjectFixture.zeros(5 * 1024 * 1024);
        var uploadResp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(1).build(), RequestBody.fromBytes(exactly5MB));

        // Upload a last part (can be small) to complete
        byte[] lastPart = ObjectFixture.zeros(ObjectFixture.SIZE_1MB);
        var uploadResp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(2).build(), RequestBody.fromBytes(lastPart));

        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(uploadResp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(uploadResp2.eTag()).build()
        );

        assertDoesNotThrow(() -> s3.completeMultipartUpload(b -> b
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build()));
    }

    @Test
    @DisplayName("Non-last part below 5MB returns EntityTooSmall")
    void testPartBelow5MB_NonLast() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-below5");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] oneBelow5MB = new byte[5 * 1024 * 1024 - 1];

        // Upload part 1 (too small for non-last)
        var uploadResp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(1).build(), RequestBody.fromBytes(oneBelow5MB));

        // Upload part 2 (also below 5MB, making part 1 non-last)
        var uploadResp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(2).build(), RequestBody.fromBytes(oneBelow5MB));

        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(uploadResp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(uploadResp2.eTag()).build()
        );

        // Complete should fail because non-last parts must be >= 5MB
        AssertS3.assertThrowsS3Exception(400, "EntityTooSmall", () -> {
            s3.completeMultipartUpload(b -> b
                    .bucket(bucket).key(key).uploadId(init.uploadId())
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        });
    }

    @Test
    @DisplayName("Part above 5MB succeeds")
    void testPartAbove5MB() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-above5");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] above5MB = ObjectFixture.zeros(5 * 1024 * 1024 + 1);
        var uploadResp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(1).build(), RequestBody.fromBytes(above5MB));

        byte[] lastPart = ObjectFixture.zeros(ObjectFixture.SIZE_1MB);
        var uploadResp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(2).build(), RequestBody.fromBytes(lastPart));

        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(uploadResp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(uploadResp2.eTag()).build()
        );

        assertDoesNotThrow(() -> s3.completeMultipartUpload(b -> b
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build()));
    }

    @Test
    @DisplayName("Last part below 5MB succeeds (no minimum for last part)")
    void testLastPartBelow5MB() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-lastsmall");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        // First part: full 5MB
        byte[] part1Data = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        var uploadResp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(1).build(), RequestBody.fromBytes(part1Data));

        // Last part: only 1MB
        byte[] part2Data = ObjectFixture.zeros(ObjectFixture.SIZE_1MB);
        var uploadResp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(2).build(), RequestBody.fromBytes(part2Data));

        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(uploadResp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(uploadResp2.eTag()).build()
        );

        assertDoesNotThrow(() -> s3.completeMultipartUpload(b -> b
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build()));
    }

    @Test
    @DisplayName("Part number 1 succeeds")
    void testPartNumber1() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-pn1");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        assertDoesNotThrow(() -> s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(1).build(), RequestBody.fromBytes(partData)));

        // Abort to clean up
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(init.uploadId()).build());
    }

    @Test
    @DisplayName("Part number 10000 succeeds")
    void testPartNumber10000() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-pn10k");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        assertDoesNotThrow(() -> s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .partNumber(10000).build(), RequestBody.fromBytes(partData)));

        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(init.uploadId()).build());
    }

    @Test
    @DisplayName("Part number 0 returns error")
    void testPartNumber0() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-pn0");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);

        AssertS3.assertThrowsS3Exception(400, "NoSuchUpload", () -> {
            s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(init.uploadId())
                    .partNumber(0).build(), RequestBody.fromBytes(partData));
        });

        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(init.uploadId()).build());
    }

    @Test
    @DisplayName("Part number 10001 returns error")
    void testPartNumber10001() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-pn10001");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);

        AssertS3.assertThrowsS3Exception(400, "NoSuchUpload", () -> {
            s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(init.uploadId())
                    .partNumber(10001).build(), RequestBody.fromBytes(partData));
        });

        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(init.uploadId()).build());
    }

    @Test
    @DisplayName("Empty body upload part behavior varies by implementation")
    void testEmptyBodyUploadPart() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-empty");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        byte[] emptyBody = new byte[0];

        // Some S3 implementations accept 0-byte parts, others reject them
        // This test documents the behavior rather than asserting success/failure
        try {
            var resp = s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(init.uploadId())
                    .partNumber(1).build(), RequestBody.fromBytes(emptyBody));
            // If accepted, clean up
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(init.uploadId()).build());
        } catch (Exception e) {
            // Expected by some implementations - empty part rejected
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(init.uploadId()).build());
        }
    }

    @Test
    @DisplayName("Multipart object integrity with multiple 5MB parts")
    void testMultipartObjectIntegrity_LargeObject() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-large");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        // Upload 3 distinct 5MB parts
        byte[] part1 = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        byte[] part2 = ObjectFixture.sequentialBytes(ObjectFixture.SIZE_5MB);
        byte[] part3 = ObjectFixture.randomBytes(ObjectFixture.SIZE_5MB);

        var resp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId()).partNumber(1).build(),
                RequestBody.fromBytes(part1));
        var resp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId()).partNumber(2).build(),
                RequestBody.fromBytes(part2));
        var resp3 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(init.uploadId()).partNumber(3).build(),
                RequestBody.fromBytes(part3));

        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(resp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(resp2.eTag()).build(),
                CompletedPart.builder().partNumber(3).eTag(resp3.eTag()).build()
        );

        s3.completeMultipartUpload(b -> b
                .bucket(bucket).key(key).uploadId(init.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());

        // Verify full content integrity
        var getResponse = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        byte[] downloaded = getResponse.readAllBytes();

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(part1);
        expected.write(part2);
        expected.write(part3);
        byte[] expectedBytes = expected.toByteArray();

        assertEquals(expectedBytes.length, downloaded.length,
                "Downloaded size should be 15MB (3 x 5MB)");

        // Byte-by-byte verification
        for (int i = 0; i < expectedBytes.length; i++) {
            if (expectedBytes[i] != downloaded[i]) {
                throw new AssertionError("Content mismatch at byte " + i);
            }
        }
    }
}
