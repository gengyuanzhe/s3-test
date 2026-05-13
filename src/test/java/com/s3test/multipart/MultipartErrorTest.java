package com.s3test.multipart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import com.s3test.fixture.BucketFixture;
import com.s3test.fixture.ObjectFixture;
import com.s3test.util.AssertS3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Multipart Upload Error Handling")
class MultipartErrorTest {

    @RegisterExtension
    static final BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("Complete with wrong part order returns InvalidPartOrder")
    void testCompleteWithWrongPartOrder() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-order");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = init.uploadId();

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);

        var resp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(partData));
        var resp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(2).build(),
                RequestBody.fromBytes(partData));

        // Declare parts in wrong order: part 2 first, then part 1
        var completedParts = List.of(
                CompletedPart.builder().partNumber(2).eTag(resp2.eTag()).build(),
                CompletedPart.builder().partNumber(1).eTag(resp1.eTag()).build()
        );

        AssertS3.assertThrowsS3Exception(400, "InvalidPartOrder", () -> {
            s3.completeMultipartUpload(b -> b
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        });
    }

    @Test
    @DisplayName("Complete with missing part returns InvalidPart")
    void testCompleteWithMissingPart() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-missing");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = init.uploadId();

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);

        // Only upload parts 1 and 2
        var resp1 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(partData));
        var resp2 = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(2).build(),
                RequestBody.fromBytes(partData));

        // Declare 3 parts but only 2 were uploaded (part 3 is missing)
        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(resp1.eTag()).build(),
                CompletedPart.builder().partNumber(2).eTag(resp2.eTag()).build(),
                CompletedPart.builder().partNumber(3).eTag("fake-etag-for-missing-part").build()
        );

        AssertS3.assertThrowsS3Exception(400, "InvalidPart", () -> {
            s3.completeMultipartUpload(b -> b
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        });
    }

    @Test
    @DisplayName("Complete with wrong ETag returns InvalidPart")
    void testCompleteWithWrongETag() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-etag");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = init.uploadId();

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);

        s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(partData));

        // Use a completely wrong ETag
        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag("\"wrong-etag-value\"").build()
        );

        AssertS3.assertThrowsS3Exception(400, "InvalidPart", () -> {
            s3.completeMultipartUpload(b -> b
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        });
    }

    @Test
    @DisplayName("Upload with invalid uploadId returns NoSuchUpload")
    void testUploadWithInvalidUploadId() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-invalid");
        String key = ObjectFixture.randomKey("mp");

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);

        AssertS3.assertThrowsS3Exception(404, "NoSuchUpload", () -> {
            s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId("nonexistent-upload-id")
                    .partNumber(1).build(), RequestBody.fromBytes(partData));
        });
    }

    @Test
    @DisplayName("Abort with invalid uploadId returns NoSuchUpload")
    void testAbortInvalidUploadId() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-abortid");

        AssertS3.assertThrowsS3Exception(404, "NoSuchUpload", () -> {
            s3.abortMultipartUpload(b -> b
                    .bucket(bucket).key("some-key")
                    .uploadId("nonexistent-upload-id").build());
        });
    }

    @Test
    @DisplayName("Complete after abort returns NoSuchUpload")
    void testCompleteAlreadyAborted() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-aborted");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = init.uploadId();

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        var uploadResp = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(partData));

        // Abort the upload
        s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId).build());

        // Try to complete the aborted upload
        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(uploadResp.eTag()).build()
        );

        AssertS3.assertThrowsS3Exception(404, "NoSuchUpload", () -> {
            s3.completeMultipartUpload(b -> b
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        });
    }

    @Test
    @DisplayName("Complete same upload twice returns error or is idempotent")
    void testCompleteTwice() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-twice");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = init.uploadId();

        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        var uploadResp = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                RequestBody.fromBytes(partData));

        var completedParts = List.of(
                CompletedPart.builder().partNumber(1).eTag(uploadResp.eTag()).build()
        );

        // First complete should succeed
        s3.completeMultipartUpload(b -> b
                .bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());

        // Second complete should either be idempotent or return an error
        try {
            s3.completeMultipartUpload(b -> b
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
            // If no exception, the implementation treats it as idempotent
        } catch (Exception e) {
            // Expected: the upload no longer exists after first complete
            // Acceptable behavior - either success or error
        }
    }

    @Test
    @DisplayName("List multipart uploads with prefix filters correctly")
    void testListMultipartUploadsWithPrefix() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-pfx");

        var init1 = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key("photos/img1.jpg").build());
        var init2 = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key("photos/img2.jpg").build());
        var init3 = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key("docs/readme.txt").build());

        try {
            // List with prefix "photos/"
            var listResponse = s3.listMultipartUploads(ListMultipartUploadsRequest.builder()
                    .bucket(bucket).prefix("photos/").build());

            List<String> keys = listResponse.uploads().stream()
                    .map(upload -> upload.key())
                    .collect(Collectors.toList());

            assertTrue(keys.contains("photos/img1.jpg"), "Should contain photos/img1.jpg");
            assertTrue(keys.contains("photos/img2.jpg"), "Should contain photos/img2.jpg");
            assertTrue(!keys.contains("docs/readme.txt"), "Should not contain docs/readme.txt");
        } finally {
            // Cleanup
            s3.abortMultipartUpload(b -> b.bucket(bucket).key("photos/img1.jpg")
                    .uploadId(init1.uploadId()).build());
            s3.abortMultipartUpload(b -> b.bucket(bucket).key("photos/img2.jpg")
                    .uploadId(init2.uploadId()).build());
            s3.abortMultipartUpload(b -> b.bucket(bucket).key("docs/readme.txt")
                    .uploadId(init3.uploadId()).build());
        }
    }

    @Test
    @DisplayName("List parts pagination returns all parts correctly")
    void testListPartsPagination() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("mpe-parts-page");
        String key = ObjectFixture.randomKey("mp");

        var init = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build());
        String uploadId = init.uploadId();

        // Upload 10 parts
        byte[] partData = ObjectFixture.zeros(ObjectFixture.SIZE_5MB);
        for (int i = 1; i <= 10; i++) {
            s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).partNumber(i).build(),
                    RequestBody.fromBytes(partData));
        }

        try {
            // List with max-parts to force pagination
            var listResp = s3.listParts(ListPartsRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .maxParts(5).build());

            assertEquals(5, listResp.parts().size(), "First page should have 5 parts");
            assertTrue(listResp.isTruncated(), "Should be truncated with more parts available");
            int nextToken = listResp.nextPartNumberMarker();

            // Fetch second page
            var listResp2 = s3.listParts(ListPartsRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .maxParts(5).partNumberMarker(nextToken).build());

            assertEquals(5, listResp2.parts().size(), "Second page should have 5 parts");

            // Collect all parts from both pages
            List<Part> allParts = new ArrayList<>();
            allParts.addAll(listResp.parts());
            allParts.addAll(listResp2.parts());

            assertEquals(10, allParts.size(), "Total parts across pages should be 10");

            // Verify part numbers
            List<Integer> partNumbers = allParts.stream()
                    .sorted(Comparator.comparingInt(Part::partNumber))
                    .map(Part::partNumber)
                    .collect(Collectors.toList());

            for (int i = 0; i < 10; i++) {
                assertEquals(i + 1, partNumbers.get(i),
                        "Part number at index " + i + " should be " + (i + 1));
            }
        } finally {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId).build());
        }
    }
}
