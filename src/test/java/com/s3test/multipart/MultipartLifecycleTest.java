package com.s3test.multipart;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Multipart Upload Lifecycle")
class MultipartLifecycleTest {

    @RegisterExtension
    static final BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("Full lifecycle: initiate -> upload 2 parts -> complete -> GET verifies content")
    void testFullLifecycle_InitiateUploadComplete() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-lifecycle");
        String key = ObjectFixture.randomKey("mp");

        // Initiate
        var initiateResponse = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = initiateResponse.uploadId();
        assertNotNull(uploadId, "UploadId should not be null");

        // Upload 2 parts
        byte[] part1Data = ObjectFixture.sequentialBytes(ObjectFixture.SIZE_5MB);
        byte[] part2Data = ObjectFixture.sequentialBytes(ObjectFixture.SIZE_5MB);

        var uploadResp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .partNumber(1).build(), RequestBody.fromBytes(part1Data));

        var uploadResp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .partNumber(2).build(), RequestBody.fromBytes(part2Data));

        // Complete
        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(uploadResp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(uploadResp2.eTag()).build()
        );

        s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());

        // GET and verify concatenated content
        var getResponse = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        byte[] downloaded = getResponse.readAllBytes();

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(part1Data);
        expected.write(part2Data);

        assertEquals(expected.size(), downloaded.length, "Total size should match sum of parts");
    }

    @Test
    @DisplayName("Content integrity: 3 distinct parts concatenated correctly")
    void testFullLifecycle_ContentIntegrity() throws Exception {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-integrity");
        String key = ObjectFixture.randomKey("mp");

        var initiateResponse = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = initiateResponse.uploadId();

        // Upload 3 parts with distinct recognizable data
        byte[] part1Data = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        byte[] part2Data = ObjectFixture.randomBytes(ObjectFixture.SIZE_5MB);
        byte[] part3Data = ObjectFixture.sequentialBytes(ObjectFixture.SIZE_5MB);

        var resp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(part1Data));
        var resp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(2).build(),
                RequestBody.fromBytes(part2Data));
        var resp3 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(3).build(),
                RequestBody.fromBytes(part3Data));

        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(resp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(resp2.eTag()).build(),
                CompletedPart.builder().partNumber(3).eTag(resp3.eTag()).build()
        );

        s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());

        // Verify full content
        var getResponse = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        byte[] downloaded = getResponse.readAllBytes();

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(part1Data);
        expected.write(part2Data);
        expected.write(part3Data);

        byte[] expectedBytes = expected.toByteArray();
        assertEquals(expectedBytes.length, downloaded.length, "Total size should be 3 * 5MB");

        // Verify each section
        for (int i = 0; i < expectedBytes.length; i++) {
            if (expectedBytes[i] != downloaded[i]) {
                throw new AssertionError("Content mismatch at byte " + i
                        + ": expected " + expectedBytes[i] + " but got " + downloaded[i]);
            }
        }
    }

    @Test
    @DisplayName("Abort multipart upload: uploadId no longer appears in list")
    void testAbortMultipartUpload() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-abort");
        String key = ObjectFixture.randomKey("mp");

        var initiateResponse = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = initiateResponse.uploadId();

        // Abort
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId).build());

        // List multipart uploads - should not contain our uploadId
        var listResponse = s3.listMultipartUploads(ListMultipartUploadsRequest.builder()
                .bucket(bucket).build());

        boolean found = listResponse.uploads().stream()
                .anyMatch(upload -> upload.uploadId().equals(uploadId));

        assertTrue(!found, "Aborted upload should not appear in list");
    }

    @Test
    @DisplayName("List parts returns all uploaded parts with correct size and ETag")
    void testListParts() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-listparts");
        String key = ObjectFixture.randomKey("mp");

        var initiateResponse = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = initiateResponse.uploadId();

        // Upload 3 parts
        int partSize = ObjectFixture.SIZE_5MB;
        var resp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(ObjectFixture.zeros(partSize)));
        var resp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(2).build(),
                RequestBody.fromBytes(ObjectFixture.zeros(partSize)));
        var resp3 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(3).build(),
                RequestBody.fromBytes(ObjectFixture.zeros(partSize)));

        // List parts
        var listResponse = s3.listParts(ListPartsRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).build());

        assertEquals(3, listResponse.parts().size(), "Should return 3 parts");

        // Sort by part number for deterministic verification
        List<Part> parts = listResponse.parts().stream()
                .sorted(Comparator.comparingInt(Part::partNumber))
                .collect(Collectors.toList());

        assertEquals(1, parts.get(0).partNumber(), "First part should be #1");
        assertEquals(2, parts.get(1).partNumber(), "Second part should be #2");
        assertEquals(3, parts.get(2).partNumber(), "Third part should be #3");

        assertEquals(partSize, parts.get(0).size(), "Part 1 size should match");
        assertEquals(partSize, parts.get(1).size(), "Part 2 size should match");
        assertEquals(partSize, parts.get(2).size(), "Part 3 size should match");

        assertNotNull(parts.get(0).eTag(), "Part 1 ETag should not be null");
        assertNotNull(parts.get(1).eTag(), "Part 2 ETag should not be null");
        assertNotNull(parts.get(2).eTag(), "Part 3 ETag should not be null");
    }

    @Test
    @DisplayName("Concurrent multipart uploads for same key have independent uploadIds")
    void testConcurrentMultipartUploads() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mp-concurrent");
        String key = ObjectFixture.randomKey("mp");

        // Initiate 3 uploads for the same key
        var init1 = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        var init2 = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        var init3 = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());

        String uploadId1 = init1.uploadId();
        String uploadId2 = init2.uploadId();
        String uploadId3 = init3.uploadId();

        // All uploadIds should be different
        assertNotEquals(uploadId1, uploadId2, "UploadIds should be unique");
        assertNotEquals(uploadId2, uploadId3, "UploadIds should be unique");
        assertNotEquals(uploadId1, uploadId3, "UploadIds should be unique");

        // List should show all 3
        var listResponse = s3.listMultipartUploads(ListMultipartUploadsRequest.builder()
                .bucket(bucket).build());

        List<String> uploadIds = listResponse.uploads().stream()
                .filter(upload -> upload.key().equals(key))
                .map(upload -> upload.uploadId())
                .collect(Collectors.toList());

        assertTrue(uploadIds.contains(uploadId1), "Should contain uploadId1");
        assertTrue(uploadIds.contains(uploadId2), "Should contain uploadId2");
        assertTrue(uploadIds.contains(uploadId3), "Should contain uploadId3");

        // Cleanup: abort all uploads
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId1).build());
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId2).build());
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId3).build());
    }
}
