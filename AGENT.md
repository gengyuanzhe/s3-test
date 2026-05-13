# AGENT.md — S3 Interface Test Suite

> AI agent knowledge base for understanding and working with this project.

## Project Overview

S3-compatible object storage interface test suite (Developer Tests / MST level).  
Purpose: verify an in-house S3-compatible storage system behaves identically to AWS S3.

- **Language**: Java 17+
- **Framework**: JUnit 5 + AWS SDK for Java v2
- **Build**: Maven
- **Scope**: Core Bucket/Object operations, Multipart Upload, Versioning
- **Total test cases**: 169 (159 active, 10 @Disabled for multi-account scenarios)

## Architecture

```
src/
├── main/java/com/s3test/          # Test infrastructure (shared by all test classes)
│   ├── config/S3TestConfig        # Endpoint/credential config from system properties
│   ├── fixture/BucketFixture      # JUnit 5 extension: auto-create & cleanup buckets
│   ├── fixture/ObjectFixture      # Test data factory (random bytes, keys, sizes)
│   ├── fixture/ObjectHelper       # Upload helper methods
│   ├── util/S3ClientFactory       # S3Client builder (path-style, configurable endpoint)
│   └── util/AssertS3              # S3-specific assertions (error codes, content comparison)
└── test/java/com/s3test/          # Test modules
    ├── bucket/                    # Bucket CRUD: Create/Delete/Head/List/Location (32 cases)
    ├── object/                    # Object CRUD + Copy + Key edge cases (77 cases)
    ├── list/                      # ListObjectsV2 (14 cases)
    ├── multipart/                 # Multipart Upload lifecycle/boundary/error (24 cases)
    └── versioning/                # Versioning control + Delete Marker + ListVersions (22 cases)
```

## How to Build & Run

```bash
# Compile
mvn compile                # main sources
mvn test-compile           # main + test sources

# Run all tests against target S3 system
mvn test -Ds3.endpoint=http://your-s3:9000 \
         -Ds3.accessKey=your-key \
         -Ds3.secretKey=your-secret \
         -Ds3.region=us-east-1

# Run single test class
mvn test -Dtest=PutObjectTest -Ds3.endpoint=...

# Run single test method
mvn test -Dtest=PutObjectTest#testPutSmallObject_Success -Ds3.endpoint=...

# Defaults (if no properties given):
#   endpoint  = http://localhost:9000
#   region    = us-east-1
#   accessKey = test-access-key
#   secretKey = test-secret-key
```

**Note**: Maven is bundled with IntelliJ IDEA at:
`C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.1\plugins\maven\lib\maven3\bin\mvn.cmd`

## Key Patterns & Conventions

### Test Class Structure

Every test class follows this pattern:

```java
class SomeTest {
    @RegisterExtension
    static BucketFixture bucketFixture = new BucketFixture();

    @Test
    @DisplayName("中文描述测试场景")
    void testScenario() {
        var s3 = bucketFixture.getClient();
        String bucket = bucketFixture.createBucket("prefix");
        // ... act & assert
    }
}
```

Key rules:
- **Always** use `@RegisterExtension BucketFixture` for automatic bucket cleanup
- **Always** use `@DisplayName` (Chinese descriptions OK)
- Get `S3Client` from `bucketFixture.getClient()`, never create directly (unless multi-account)
- Each test method is self-contained: setup → act → assert

### Error Scenario Pattern

```java
// CORRECT: use AssertS3 helper
AssertS3.assertThrowsS3Exception(404, "NoSuchBucket", () -> {
    s3.someOperation(...);
});

// WRONG: manual try/catch
try { ... } catch (S3Exception e) { ... }  // Don't do this
```

### Test Data

```java
ObjectFixture.randomBytes(size)      // Random data
ObjectFixture.randomKey()            // Random object key: "test-obj-abc12345"
ObjectFixture.randomKey("prefix/")   // Keyed: "prefix/abc12345"
ObjectFixture.SIZE_1KB / SIZE_1MB / SIZE_5MB  // Common sizes

ObjectHelper.putObject(s3, bucket, key, data)  // Quick upload
ObjectHelper.putObjectWithMetadata(s3, bucket, key, data, metadata)
ObjectHelper.putObjectWithContentType(s3, bucket, key, data, contentType)
```

### Bucket Naming

`BucketFixture.createBucket("prefix")` generates names like `s3test-prefix-1-1a2b3c4d5`.  
All created buckets are force-deleted (including objects and versions) in `@AfterEach`.

### @Disabled Tests

Tests requiring multi-account or special setup are marked `@Disabled` with a comment explaining why:
- `testCreateBucket_NoPermission` — requires read-only credentials
- `testDeleteBucket_NoPermission` — requires cross-account bucket
- `testListBuckets_EmptyAccount` — requires empty account

Enable these when the test environment supports them.

## S3 Compatibility Focus Areas

The following are common failure points for self-developed S3 systems. Tests specifically target these:

| Area | Key Tests | Why It Fails |
|------|-----------|-------------|
| Error code accuracy | Every module's error cases | Wrong HTTP status or error code (e.g., 500 vs 400) |
| Delete idempotency | `DeleteObjectTest#testDeleteNonExistentObject` | Should return 204, many return 404 |
| Range request boundaries | `GetObjectTest` range tests | Off-by-one in byte range calculation |
| List consistency | `ListObjectsV2Test#testNewObjectImmediatelyVisible` | Write-then-list may not show new key |
| Multipart part size | `MultipartBoundaryTest#testPartBelow5MB_NonLast` | Non-last part < 5MB must be rejected |
| Delete Marker semantics | `DeleteMarkerTest` all cases | Confusing soft delete vs permanent delete |
| Key encoding | `ObjectKeyEdgeCaseTest` all cases | Chinese/special chars not handled |
| Bucket name validation | `CreateBucketTest` invalid name cases | DNS naming rules not fully enforced |

## Test Plan Document

Full test case specification with priority, preconditions, and expected results:
→ `S3-Interface-Test-Plan.md`

## Adding New Tests

1. Create test class in appropriate package under `src/test/java/com/s3test/`
2. Add `@RegisterExtension static BucketFixture bucketFixture = new BucketFixture();`
3. Use `@DisplayName` and `@Test` for each case
4. For error scenarios, use `AssertS3.assertThrowsS3Exception(statusCode, errorCode, () -> {...})`
5. For `getObject().readAllBytes()`, add `throws Exception` to method signature
6. Multi-account tests → mark `@Disabled`
7. Run `mvn test-compile` to verify compilation before committing

## Future Expansion (Not Yet Implemented)

The test plan documents additional modules for future implementation:
- ACL (Bucket/Object ACL operations)
- Bucket Policy
- CORS configuration
- Server-Side Encryption (SSE-S3, SSE-KMS, SSE-C)
- Object Tagging
- Object Lock / Retention / Legal Hold
- Lifecycle Configuration
- Presigned URL
- Storage Class transitions
