# S3 兼容存储系统 — 接口开发者测试（DT）计划

> **版本**: v1.0  
> **适用系统**: 自研 S3 兼容对象存储  
> **测试级别**: 接口级 MST（Method/Service Test）  
> **技术栈**: Java 17+ / JUnit 5 / AWS SDK for Java v2  
> **测试范围**: Bucket 操作、Object CRUD、List、Copy、Multipart Upload、Versioning

---

## 一、测试目标

作为自研 S3 兼容存储系统，核心目标是 **行为与 AWS S3 一致**。DT 侧重验证：

| 维度 | 说明 |
|------|------|
| **功能正确性** | 每个 API 的 Happy Path 行为符合 S3 规范 |
| **兼容性** | 返回值结构、HTTP 状态码、错误码与 AWS S3 一致 |
| **边界健壮性** | 大小限制、Key 约束、分页边界、并发安全 |
| **错误处理** | 异常场景返回正确的错误码和 HTTP 状态 |
| **一致性** | Read-After-Write 行为正确（强一致或最终一致） |

---

## 二、项目结构建议

```
s3test/
├── pom.xml                                    # Maven 项目配置
├── S3-Interface-Test-Plan.md                  # 本文档
└── src/
    ├── main/
    │   └── java/com/s3test/
    │       ├── config/
    │       │   └── S3TestConfig.java          # Endpoint/Credential 配置
    │       ├── fixture/
    │       │   ├── BucketFixture.java         # Bucket 生命周期管理
    │       │   ├── ObjectFixture.java         # 测试数据工厂
    │       │   └── RandomDataGenerator.java   # 随机数据生成
    │       └── util/
    │           ├── S3ClientFactory.java       # S3Client 创建（支持多账号）
    │           └── AssertS3.java              # S3 专用断言工具
    └── test/
        └── java/com/s3test/
            ├── bucket/
            │   ├── CreateBucketTest.java
            │   ├── DeleteBucketTest.java
            │   ├── HeadBucketTest.java
            │   ├── ListBucketsTest.java
            │   └── GetBucketLocationTest.java
            ├── object/
            │   ├── PutObjectTest.java
            │   ├── GetObjectTest.java
            │   ├── HeadObjectTest.java
            │   ├── DeleteObjectTest.java
            │   ├── DeleteObjectsTest.java
            │   ├── CopyObjectTest.java
            │   └── ObjectKeyEdgeCaseTest.java
            ├── list/
            │   ├── ListObjectsV2Test.java
            │   └── ListObjectVersionsTest.java
            ├── multipart/
            │   ├── MultipartLifecycleTest.java
            │   ├── MultipartBoundaryTest.java
            │   └── MultipartErrorTest.java
            └── versioning/
                ├── BucketVersioningTest.java
                ├── VersionedObjectOpsTest.java
                └── DeleteMarkerTest.java
```

---

## 三、测试基础设施

### 3.1 依赖（pom.xml 关键部分）

```xml
<dependencies>
    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.2</version>
        <scope>test</scope>
    </dependency>
    <!-- AWS SDK v2 -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>s3</artifactId>
        <version>2.25.0</version>
    </dependency>
    <!-- 参数化测试 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter-params</artifactId>
        <version>5.10.2</version>
        <scope>test</scope>
    </dependency>
    <!-- 数据驱动 -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.17.0</version>
    </dependency>
</dependencies>
```

### 3.2 S3Client 工厂

```java
// 支持通过环境变量或配置文件切换目标系统
public class S3ClientFactory {
    public static S3Client create() {
        return S3Client.builder()
            .endpointOverride(URI.create(System.getProperty("s3.endpoint", "http://localhost:9000")))
            .region(Region.of(System.getProperty("s3.region", "us-east-1")))
            .credentialsOverride(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    System.getProperty("s3.accessKey", "test-access-key"),
                    System.getProperty("s3.secretKey", "test-secret-key")
                )
            ))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)  // 自研系统通常用 PathStyle
                .build())
            .build();
    }
}
```

### 3.3 公共 Fixture 模式

```java
// Bucket 生命周期：每个测试方法前创建、测试后清理
public class BucketFixture implements BeforeEachCallback, AfterEachCallback {
    // 自动跟踪并清理测试创建的 Bucket
}

// 对象数据工厂
public class ObjectFixture {
    public static byte[] randomBytes(int size);          // 随机数据
    public static byte[] zeros(int size);                 // 全零
    public static byte[] sequentialBytes(int size);       // 0x00-0xFF 循环
    public static String randomKey();                     // 随机对象 Key
    public static String randomKey(String prefix);        // 带 prefix 的随机 Key
}
```

---

## 四、测试用例详细设计

> **优先级定义**:  
> **P0** = 冒烟/核心，必须通过才能上线  
> **P1** = 高频场景，影响主流程  
> **P2** = 边界/异常，影响兼容性评分  
> **P3** = 罕见场景，长期覆盖  

---

### 4.1 Bucket 操作模块

#### 4.1.1 CreateBucket

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | 正常创建 Bucket | P0 | 无 | `CreateBucket("test-bucket-001")` | 200 OK，location 返回正确 |
| 2 | 创建后验证存在 | P0 | 创建完成 | `HeadBucket("test-bucket-001")` | 200 OK |
| 3 | Bucket 名称最小长度(3字符) | P1 | 无 | `CreateBucket("ab3")` | 200 OK |
| 4 | Bucket 名称最大长度(63字符) | P1 | 无 | `CreateBucket("a"...63chars)` | 200 OK |
| 5 | 重复创建同名 Bucket（同账号） | P1 | Bucket 已存在 | `CreateBucket(已存在的名字)` | 409 `BucketAlreadyOwnedByYou` |
| 6 | 重复创建同名 Bucket（不同账号） | P2 | 另一账号的 Bucket 已存在 | 用 B 账号创建 A 的 Bucket 名 | 409 `BucketAlreadyExists` |
| 7 | Bucket 名称含大写字母 | P2 | 无 | `CreateBucket("TestBucket")` | 400 `InvalidBucketName` |
| 8 | Bucket 名称含特殊字符 | P2 | 无 | `CreateBucket("test.bucket")` | 400 `InvalidBucketName` |
| 9 | Bucket 名称以连字符开头/结尾 | P2 | 无 | `CreateBucket("-test")` / `CreateBucket("test-")` | 400 `InvalidBucketName` |
| 10 | Bucket 名称为空 | P2 | 无 | `CreateBucket("")` | 400 `InvalidBucketName` |
| 11 | Bucket 名称过短(1-2字符) | P2 | 无 | `CreateBucket("ab")` | 400 `InvalidBucketName` |
| 12 | Bucket 名称过长(>63字符) | P2 | 无 | `CreateBucket(64字符)` | 400 `InvalidBucketName` |
| 13 | 无权限创建 | P2 | 只读账号 | `CreateBucket(...)` | 403 `AccessDenied` |
| 14 | Bucket 名称格式为IP | P3 | 无 | `CreateBucket("192.168.1.1")` | 400 `InvalidBucketName` |
| 15 | Bucket 名称前缀 xn-- | P3 | 无 | `CreateBucket("xn--test")` | 400 `InvalidBucketName` |
| 16 | Bucket 名称前缀 sthree- | P3 | 无 | `CreateBucket("sthree-test")` | 400 `InvalidBucketName` |
| 17 | 带 Region 创建 | P1 | 无 | `CreateBucket` + `LocationConstraint` | 200 OK，GetBucketLocation 返回正确 region |

**用例数**: 17 | **自动化建议**: 3-7 使用 `@ParameterizedTest` 参数化

---

#### 4.1.2 DeleteBucket

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | 删除空 Bucket | P0 | 空的 Bucket 存在 | `DeleteBucket(bucket)` | 204 No Content |
| 2 | 删除后 HeadBucket | P0 | 删除完成 | `HeadBucket(bucket)` | 404 `NoSuchBucket` |
| 3 | 删除非空 Bucket | P1 | Bucket 内有对象 | `DeleteBucket(非空bucket)` | 409 `BucketNotEmpty` |
| 4 | 删除不存在的 Bucket | P1 | Bucket 不存在 | `DeleteBucket(不存在的名)` | 404 `NoSuchBucket` |
| 5 | 删除无权限的 Bucket | P2 | 其他账号的 Bucket | `DeleteBucket(他人bucket)` | 403 `AccessDenied` |
| 6 | 删除含未完成 Multipart 的 Bucket | P2 | 有进行中的分片上传 | `DeleteBucket(bucket)` | 409 `BucketNotEmpty`（或要求先清理） |

**用例数**: 6

---

#### 4.1.3 HeadBucket

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | 存在的 Bucket | P0 | `HeadBucket(存在)` | 200 OK |
| 2 | 不存在的 Bucket | P0 | `HeadBucket(不存在)` | 404 `NoSuchBucket` |
| 3 | 无权限的 Bucket | P2 | `HeadBucket(无权)` | 403 `AccessDenied` |

**用例数**: 3

---

#### 4.1.4 ListBuckets

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | 列出当前账号所有 Bucket | P0 | 创建 N 个后 `ListBuckets()` | 包含全部 N 个 |
| 2 | 空账号列出 Bucket | P1 | 无 Bucket 时 `ListBuckets()` | 返回空列表 |
| 3 | 只列出自己的 Bucket | P2 | 多账号场景 | 不包含其他账号的 Bucket |
| 4 | Bucket 创建后立即可见 | P1 | `CreateBucket` 后立即 `ListBuckets` | 新 Bucket 在列表中 |

**用例数**: 4

---

#### 4.1.5 GetBucketLocation

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | 获取存在的 Bucket Location | P1 | `GetBucketLocation(bucket)` | 返回正确 region |
| 2 | 获取不存在的 Bucket | P1 | `GetBucketLocation(不存在)` | 404 `NoSuchBucket` |

**用例数**: 2

> **Bucket 模块合计**: 32 个用例

---

### 4.2 Object CRUD 模块

#### 4.2.1 PutObject

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| **— 正常路径 —** |
| 1 | 上传小对象(< 1KB) | P0 | Bucket 存在 | `PutObject(bucket, key, data)` | 200 OK，返回 ETag |
| 2 | 上传后 GetObject 内容一致 | P0 | 上传完成 | `GetObject` 并比对 byte[] | 内容完全一致 |
| 3 | 上传 0 字节对象 | P0 | — | `PutObject(bucket, key, empty)` | 200 OK，Content-Length=0 |
| 4 | 上传中等大小对象(1MB) | P1 | — | `PutObject(bucket, key, 1MB)` | 200 OK，ETag 正确 |
| 5 | 上传大对象(接近 5GB) | P2 | — | `PutObject(bucket, key, ~5GB)` | 200 OK（单次 PUT 上限 5GB） |
| **— 覆盖与幂等 —** |
| 6 | 覆盖已有对象 | P0 | 对象已存在 | `PutObject(bucket, sameKey, newData)` | 200 OK，GET 返回新数据 |
| 7 | 多次覆盖幂等 | P1 | — | 连续 3 次 Put 同 Key | 最后一次内容为准 |
| **— Metadata —** |
| 8 | 上传带 Content-Type | P1 | — | `PutObject` + `Content-Type: application/json` | GET/HEAD 返回相同 Content-Type |
| 9 | 上传带用户自定义 Metadata | P1 | — | `PutObject` + `x-amz-meta-*` headers | GET/HEAD 返回相同 metadata |
| 10 | Metadata 超过 2KB | P2 | — | 设置超大 metadata | 400 `MetadataTooLarge` |
| 11 | Metadata 含非 ASCII 字符 | P3 | — | `x-amz-meta-*` 含中文 | 正确存储或返回错误（视兼容策略） |
| **— Content-MD5 校验 —** |
| 12 | 正确的 Content-MD5 | P1 | — | `PutObject` + 正确 MD5 | 200 OK |
| 13 | 错误的 Content-MD5 | P1 | — | `PutObject` + 错误 MD5 | 400 `BadDigest` |
| **— 条件写入 —** |
| 14 | If-None-Match: * 且 Key 不存在 | P1 | Key 不存在 | `PutObject` + `If-None-Match: *` | 200 OK |
| 15 | If-None-Match: * 且 Key 已存在 | P1 | Key 已存在 | `PutObject` + `If-None-Match: *` | 412 `PreconditionFailed` |
| **— 错误场景 —** |
| 16 | Bucket 不存在 | P0 | — | `PutObject(不存在的bucket, key, data)` | 404 `NoSuchBucket` |
| 17 | 无权限 | P2 | 只读权限 | `PutObject` | 403 `AccessDenied` |
| 18 | 对象超过 5GB | P2 | — | `PutObject` 大于 5GB | 400 `EntityTooLarge` |
| 19 | Key 长度超过 1024 字节 | P2 | — | 超长 Key | 400 `KeyTooLongError` |
| 20 | 缺少 Content-Length | P3 | — | 不发送 Content-Length header | 411 `MissingContentLength` |

**用例数**: 20

---

#### 4.2.2 GetObject

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| **— 正常路径 —** |
| 1 | 获取完整对象 | P0 | 对象存在 | `GetObject(bucket, key)` | 200 OK，内容正确 |
| 2 | 获取 0 字节对象 | P0 | 0 字节对象存在 | `GetObject(bucket, key)` | 200 OK，Content-Length=0 |
| 3 | 获取后验证全部 Metadata | P1 | 上传时带 metadata | `GetObject` 并比对 metadata | 全部 metadata 一致 |
| **— Range 请求 —** |
| 4 | Range: bytes=0-9 (前10字节) | P0 | 1MB 对象 | `GetObject` + Range header | 206 Partial Content，返回前 10 字节 |
| 5 | Range: bytes=100-199 (中间段) | P1 | — | `GetObject` + Range | 206，返回指定范围 |
| 6 | Range: bytes=-100 (最后100字节) | P1 | — | `GetObject` + Range | 206，返回最后 100 字节 |
| 7 | Range: bytes=0- (从开头到末尾) | P2 | — | `GetObject` + Range | 206 或 200，返回全部内容 |
| 8 | Range 超出对象大小 | P2 | 1KB 对象 | `Range: bytes=100000-200000` | 416 `InvalidRange` |
| **— 条件获取 —** |
| 9 | If-Match 匹配 | P1 | 已知 ETag | `GetObject` + `If-Match: etag` | 200 OK |
| 10 | If-Match 不匹配 | P1 | — | `GetObject` + `If-Match: wrong-etag` | 412 `PreconditionFailed` |
| 11 | If-None-Match 匹配(未修改) | P1 | — | `GetObject` + `If-None-Match: etag` | 304 `NotModified` |
| 12 | If-Modified-Since 未修改 | P2 | — | `GetObject` + `If-Modified-Since: 未来时间` | 304 `NotModified` |
| 13 | If-Unmodified-Since 已修改 | P2 | — | `GetObject` + `If-Unmodified-Since: 过去时间` | 412 `PreconditionFailed` |
| **— response-override 参数 —** |
| 14 | response-content-type 覆盖 | P2 | — | `GetObject?response-content-type=application/pdf` | 返回 Content-Type: application/pdf |
| 15 | response-content-disposition | P3 | — | `GetObject?response-content-disposition=attachment` | 返回对应 header |
| **— 错误场景 —** |
| 16 | 对象不存在 | P0 | — | `GetObject(bucket, 不存在的key)` | 404 `NoSuchKey` |
| 17 | Bucket 不存在 | P0 | — | `GetObject(不存在的bucket, key)` | 404 `NoSuchBucket` |
| 18 | 无权限 | P2 | — | `GetObject(无权bucket, key)` | 403 `AccessDenied` |

**用例数**: 18

---

#### 4.2.3 HeadObject

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | 存在的对象 | P0 | `HeadObject(bucket, key)` | 200 OK，ETag/Content-Length/Last-Modified 正确 |
| 2 | 不存在的对象 | P0 | `HeadObject(bucket, 不存在的key)` | 404 `NoSuchKey`（或 404 无 body） |
| 3 | 验证 Metadata 完整性 | P1 | 上传带 metadata 后 HEAD | 全部 metadata header 正确返回 |
| 4 | 验证 Content-Length 与实际上传大小一致 | P1 | 上传 N 字节后 HEAD | Content-Length == N |
| 5 | 条件 HEAD (If-Match) | P2 | HEAD + If-Match | 200 或 412 |
| 6 | 条件 HEAD (If-None-Match) | P2 | HEAD + If-None-Match | 200 或 304 |

**用例数**: 6

---

#### 4.2.4 DeleteObject

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | 删除存在的对象 | P0 | 对象存在 | `DeleteObject(bucket, key)` | 204 No Content |
| 2 | 删除后 GetObject | P0 | 删除完成 | `GetObject(bucket, key)` | 404 `NoSuchKey` |
| 3 | 删除不存在的对象 | P1 | Key 不存在 | `DeleteObject(bucket, 不存在)` | 204（幂等，S3 规范如此） |
| 4 | 删除无权限的对象 | P2 | — | `DeleteObject(无权bucket, key)` | 403 `AccessDenied` |
| 5 | 删除后验证不在 List 中 | P1 | 删除完成 | `ListObjectsV2(bucket)` | 列表中不包含该 key |

**用例数**: 5

---

#### 4.2.5 DeleteObjects（批量删除）

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | 批量删除全部存在的对象 | P0 | N 个对象存在 | `DeleteObjects(keys=[k1,k2,k3])` | 每个返回 `Deleted` |
| 2 | 批量删除含不存在的 Key | P1 | k1 存在，k2 不存在 | `DeleteObjects(keys=[k1,k2])` | k1=Deleted, k2=Deleted（S3 幂等） |
| 3 | 批量删除 1000 个对象(上限) | P1 | 1000 个对象 | `DeleteObjects(1000个key)` | 全部成功 |
| 4 | 超过 1000 个对象 | P2 | — | `DeleteObjects(1001个key)` | 错误拒绝 |
| 5 | Quiet 模式 | P2 | — | `DeleteObjects(quiet=true)` | 响应不包含 Deleted 列表 |
| 6 | Verbose 模式(默认) | P1 | — | `DeleteObjects(quiet=false)` | 响应包含每个 key 的 Deleted 状态 |

**用例数**: 6

---

#### 4.2.6 CopyObject

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | 同 Bucket 内复制 | P0 | 源对象存在 | `CopyObject(srcBkt, srcKey → sameBkt, dstKey)` | 200 OK，目标对象存在且内容一致 |
| 2 | 跨 Bucket 复制 | P0 | 两个 Bucket 均存在 | `CopyObject(bkt1, key → bkt2, key)` | 目标 Bucket 出现对象 |
| 3 | 复制后内容一致 | P0 | 复制完成 | GET 源和目标，比较 byte[] | 完全一致 |
| 4 | 复制时 Metadata Directive=COPY | P1 | 源带 metadata | `CopyObject` + `COPY` directive | 目标继承源 metadata |
| 5 | 复制时 Metadata Directive=REPLACE | P1 | — | `CopyObject` + `REPLACE` + 新 metadata | 目标使用新 metadata |
| 6 | 复制到自身(同 Key) | P1 | 对象存在 | `CopyObject(bkt, key → bkt, sameKey)` | 取决于 Versioning 状态 |
| 7 | 源对象不存在 | P1 | — | `CopyObject(不存在的src)` | 404 `NoSuchKey` |
| 8 | 目标 Bucket 不存在 | P1 | — | `CopyObject → 不存在的bucket` | 404 `NoSuchBucket` |
| 9 | 无权限读取源 | P2 | — | `CopyObject(无权src)` | 403 `AccessDenied` |
| 10 | 复制 0 字节对象 | P2 | — | `CopyObject(0字节src)` | 目标 0 字节 |
| 11 | 复制带 Tagging Directive | P3 | 源有 tags | `COPY` vs `REPLACE` tagging | 验证 tag 继承行为 |

**用例数**: 11

---

#### 4.2.7 Object Key 边界测试（专项）

| # | 测试场景 | 优先级 | Key 值 | 预期结果 |
|---|---------|--------|--------|---------|
| 1 | 单字符 Key | P1 | `"a"` | 正常上传下载 |
| 2 | 最大长度 Key (1024 字节) | P2 | 1024 字节 UTF-8 | 正常上传下载 |
| 3 | 超长 Key (>1024 字节) | P2 | 1025 字节 | 400 `KeyTooLongError` |
| 4 | 中文 Key | P1 | `"测试/文件.txt"` | URL 编码后正常处理 |
| 5 | 特殊安全字符 | P2 | `"a-z_A.Z 0+9"` | 正常处理 |
| 6 | 需编码字符 | P2 | `"key&val=1"` | URL 编码后正常处理 |
| 7 | 路径分隔符模拟目录 | P1 | `"a/b/c/d/e.txt"` | 正常处理 |
| 8 | 末尾斜杠 | P2 | `"dir/"` | 正常（不是真目录） |
| 9 | 双斜杠 | P2 | `"a//b"` | 正常 |
| 10 | 以 `.` 开头 | P3 | `".hidden"` | 正常 |
| 11 | Unicode 字符 | P3 | `"🔑emoji"` | URL 编码后正常或明确拒绝 |

**用例数**: 11

> **Object CRUD 模块合计**: 77 个用例

---

### 4.3 List 操作模块

#### 4.3.1 ListObjectsV2

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | 列出 Bucket 内所有对象 | P0 | N 个对象 | `ListObjectsV2(bucket)` | 返回全部 N 个 |
| 2 | 空 Bucket | P0 | 空 Bucket | `ListObjectsV2(bucket)` | 返回空列表 |
| 3 | Prefix 过滤 | P0 | keys: `a/1`, `a/2`, `b/1` | `ListObjectsV2(prefix="a/")` | 只返回 `a/1`, `a/2` |
| 4 | Delimiter 分组 | P1 | keys: `a/1`, `a/2`, `b/1` | `ListObjectsV2(delimiter="/")` | CommonPrefixes=["a/","b/"] |
| 5 | Prefix + Delimiter 组合 | P1 | 多层级 Key | `prefix="a/", delimiter="/"` | 正确分组 |
| 6 | max-keys 分页 | P0 | 20 个对象 | `max-keys=5` | 返回 5 个 + continuation-token |
| 7 | 续翻页到末尾 | P0 | — | 循环翻页直到 `IsTruncated=false` | 总数等于 20 |
| 8 | max-keys=0 | P2 | — | `max-keys=0` | 返回 0 个 key，但返回 CommonPrefixes |
| 9 | max-keys=1000(上限) | P2 | 1000+ 对象 | `max-keys=1000` | 最多返回 1000 |
| 10 | start-after 参数 | P1 | — | `start-after="key5"` | 返回 key5 之后的对象 |
| 11 | 创建后立即可见 | P1 | `PutObject` 后立即 `List` | 新 Key 在列表中 | 强一致验证 |
| 12 | 删除后立即不可见 | P1 | `DeleteObject` 后立即 `List` | 已删 Key 不在列表中 | 强一致验证 |
| 13 | fetch-owner=true | P3 | — | `fetch-owner=true` | 包含 Owner 信息 |
| 14 | encoding-type=url | P3 | Key 含特殊字符 | `encoding-type=url` | Key URL 编码返回 |

**用例数**: 14

---

#### 4.3.2 ListObjectVersions（Versioning 开启时）

> 见 4.5 Versioning 模块

> **List 模块合计**: 14 个用例（不含 ListObjectVersions）

---

### 4.4 Multipart Upload 模块

#### 4.4.1 完整生命周期测试

| # | 测试场景 | 优先级 | 操作流程 | 预期结果 |
|---|---------|--------|---------|---------|
| 1 | 完整流程: Initiate → Upload → Complete | P0 | Initiate → 上传 2 个 Part → Complete | 200 OK，GET 返回拼接后的完整内容 |
| 2 | 完成后验证对象完整性 | P0 | — | GET 对象内容 = Part1 + Part2 + Part3 |
| 3 | Initiate → Abort | P0 | Initiate → Abort | 204，ListMultipartUploads 中不再包含 |
| 4 | Initiate → 上传 Part → ListParts | P1 | 上传 3 个 Part 后 ListParts | 返回 3 个 Part 的 ETag 和 Size |
| 5 | 多个并发 Multipart Upload | P1 | 同时 Initiate 3 个 | UploadId 不同，互不影响 |

**用例数**: 5

---

#### 4.4.2 边界值测试

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | Part 恰好 5MB (非最后一个) | P1 | `UploadPart(5MB)` | 成功 |
| 2 | Part 5MB-1 字节 (非最后一个) | P1 | `UploadPart(5MB-1)` | 400 `EntityTooSmall` |
| 3 | Part 5MB+1 字节 | P1 | `UploadPart(5MB+1)` | 成功 |
| 4 | 最后一个 Part < 5MB | P0 | `Complete` 时最后一个 Part 1MB | 成功（最后一个 Part 无最小限制） |
| 5 | Part 编号 1 | P1 | `UploadPart(partNumber=1)` | 成功 |
| 6 | Part 编号 10000(上限) | P2 | `UploadPart(partNumber=10000)` | 成功 |
| 7 | Part 编号 0 | P2 | `UploadPart(partNumber=0)` | 错误 |
| 8 | Part 编号 10001(超出) | P2 | `UploadPart(partNumber=10001)` | 错误 |
| 9 | 组装后对象恰好 5TB (理论上限) | P3 | 多 Part 总计 5TB | 成功（视系统能力） |
| 10 | 空 Body 上传 Part | P3 | `UploadPart(body=empty)` | 行为取决于实现（AWS 允许最后一个） |

**用例数**: 10

---

#### 4.4.3 错误场景测试

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | Complete 时 Part 顺序错误 | P1 | Part 编号乱序 Complete | 400 `InvalidPartOrder` |
| 2 | Complete 时缺少 Part | P1 | 只上传 2 个 Part 但声明 3 个 | 400 `InvalidPart` |
| 3 | Complete 时 ETag 不匹配 | P1 | 使用错误的 ETag | 400 `InvalidPart` |
| 4 | UploadId 不存在 | P0 | `UploadPart(uploadId=不存在)` | 404 `NoSuchUpload` |
| 5 | Abort 不存在的 UploadId | P1 | `AbortMultipartUpload(无效ID)` | 404 `NoSuchUpload` |
| 6 | Complete 已 Abort 的 Upload | P1 | Abort 后 Complete | 404 `NoSuchUpload` |
| 7 | 重复 Complete 同一 UploadId | P2 | Complete 后再次 Complete | 错误或幂等 |
| 8 | ListMultipartUploads Prefix 过滤 | P2 | 多个 Upload 加 Prefix 过滤 | 返回匹配的 |
| 9 | ListParts 分页 | P2 | 上传大量 Part | 分页正确 |

**用例数**: 9

> **Multipart 模块合计**: 24 个用例

---

### 4.5 Versioning 模块

#### 4.5.1 Bucket Versioning 控制

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | 查询默认 Versioning 状态 | P0 | 新 Bucket `GetBucketVersioning` | 未启用（无 Enable 状态） |
| 2 | 启用 Versioning | P0 | `PutBucketVersioning(Enabled)` | Get 返回 Enabled |
| 3 | 暂停 Versioning | P1 | `PutBucketVersioning(Suspended)` | Get 返回 Suspended |
| 4 | 重复启用 | P2 | 已 Enabled 再次 Enabled | 幂等，仍为 Enabled |
| 5 | 不存在的 Bucket | P1 | `GetBucketVersioning(不存在)` | 404 `NoSuchBucket` |

**用例数**: 5

---

#### 4.5.2 版本化对象操作

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | PUT 创建对象返回 versionId | P0 | Versioning 已启用 | `PutObject` | 响应含 versionId |
| 2 | 覆盖产生新版本 | P0 | 对象已存在 | `PutObject(sameKey, newData)` | 返回新的 versionId，旧版本保留 |
| 3 | GET 不指定版本=最新版本 | P0 | 2 个版本 | `GetObject(bucket, key)` | 返回最新版本内容 |
| 4 | GET 指定 versionId | P0 | — | `GetObject(bucket, key, versionId=v1)` | 返回 v1 的内容 |
| 5 | GET 不存在的 versionId | P1 | — | `GetObject(bucket, key, versionId=不存在)` | 404 `NoSuchVersion` |
| 6 | HEAD 指定版本 | P1 | — | `HeadObject(bucket, key, versionId=v1)` | 返回 v1 的 metadata |
| 7 | 同一 Key 3 次覆盖，List 验证 3 个版本 | P0 | — | `ListObjectVersions(bucket)` | 返回 3 个版本 |

**用例数**: 7

---

#### 4.5.3 Delete Marker

| # | 测试场景 | 优先级 | 前置条件 | 操作 | 预期结果 |
|---|---------|--------|---------|------|---------|
| 1 | DELETE 创建 Delete Marker | P0 | Versioning 启用，对象存在 | `DeleteObject(bucket, key)` | 返回 versionId（Delete Marker 的），GET 返回 404 |
| 2 | Delete Marker 不删除历史版本 | P0 | 2 个版本 | DELETE 后 `ListObjectVersions` | 历史版本仍存在 + Delete Marker |
| 3 | 删除 Delete Marker 恢复对象 | P1 | Delete Marker 存在 | `DeleteObject(versionId=markerId)` | GET 恢复返回最新历史版本 |
| 4 | 指定 versionId 永久删除 | P0 | — | `DeleteObject(versionId=具体版本)` | 该版本永久删除，不是 Delete Marker |
| 5 | 对 Delete Marker 执行 GET | P1 | — | `GetObject(versionId=markerId)` | 405 Method Not Allowed 或 404 |

**用例数**: 5

---

#### 4.5.4 ListObjectVersions

| # | 测试场景 | 优先级 | 操作 | 预期结果 |
|---|---------|--------|------|---------|
| 1 | 列出所有版本 | P0 | `ListObjectVersions(bucket)` | 包含所有对象的所有版本 + Delete Markers |
| 2 | Prefix 过滤 | P1 | `ListObjectVersions(prefix="dir/")` | 只返回匹配前缀的 |
| 3 | Key Marker 分页 | P1 | 大量版本 | key-marker + version-id-marker 分页 |
| 4 | max-keys 分页 | P1 | — | 分页逻辑正确 |
| 5 | Versioning 未启用时调用 | P2 | — | 正常返回（仅一个 null versionId） |

**用例数**: 5

> **Versioning 模块合计**: 22 个用例

---

## 五、测试优先级总结与执行策略

### 5.1 模块级优先级

| 模块 | 用例数 | 优先级 | 建议实施顺序 |
|------|-------|--------|-------------|
| Object CRUD (Put/Get/Head/Delete) | 49 | **P0** | 第 1 批 |
| Bucket 操作 | 32 | **P0** | 第 1 批 |
| ListObjectsV2 | 14 | **P0** | 第 2 批 |
| CopyObject | 11 | **P1** | 第 2 批 |
| DeleteObjects (批量) | 6 | **P1** | 第 2 批 |
| Object Key 边界 | 11 | **P2** | 第 3 批 |
| Multipart Upload | 24 | **P1** | 第 3 批 |
| Versioning | 22 | **P1** | 第 4 批 |
| **总计** | **169** | — | — |

### 5.2 执行策略

```
第 1 批 (P0, 约 95 个用例): 基础 CRUD 通不过，其他免谈
├── PutObject / GetObject / HeadObject / DeleteObject
├── CreateBucket / DeleteBucket / HeadBucket / ListBuckets
└── ListObjectsV2 基本场景

第 2 批 (P1-High, 约 31 个用例): 补全高频操作
├── CopyObject
├── DeleteObjects
└── ListObjectsV2 高级场景 (Delimiter, Prefix 组合)

第 3 批 (P1+P2, 约 35 个用例): 复杂场景 + 边界
├── Multipart Upload 完整流程
├── Object Key 边界测试
└── 条件请求 (If-Match / If-None-Match)

第 4 批 (P1, 约 22 个用例): Versioning
├── Bucket Versioning 控制
├── 版本化对象操作
├── Delete Marker
└── ListObjectVersions
```

### 5.3 自研 S3 重点验证清单

作为自研系统，以下是最容易出现兼容性问题的「高危区」：

| 高危区 | 典型问题 | 覆盖用例 |
|--------|---------|---------|
| **错误码准确性** | 返回 500 而非 400，或 `NoSuchBucket` vs `NoSuchKey` 混淆 | 每个模块的错误场景 |
| **Delete 幂等性** | 删除不存在的对象应返回 204，很多自研系统返回 404 | DeleteObject #3 |
| **ETag 计算** | Multipart 的 ETag 计算方式不同 | Multipart #2 |
| **Range 请求** | 边界计算 off-by-one | GetObject #4-8 |
| **List 一致性** | 写入后立即 List 可能不可见 | List #11, #12 |
| **Delete Marker 语义** | 与永久删除混淆 | Versioning #4.5.3 |
| **Multipart Part 限制** | 非最后 Part 最小 5MB 校验缺失 | Multipart #4.4.2 #2 |
| **Key 编码** | 中文/特殊字符处理不一致 | Key 边界 #4-6 |
| **批量删除响应格式** | quiet 模式/verbose 模式行为差异 | DeleteObjects #5-6 |

---

## 六、后续扩展建议

以下操作不在本次测试范围内，建议后续迭代覆盖：

| 类别 | 操作 | 优先级建议 |
|------|------|-----------|
| ACL | GetBucketAcl / PutBucketAcl / GetObjectAcl / PutObjectAcl | P2 |
| Bucket Policy | GetBucketPolicy / PutBucketPolicy / DeleteBucketPolicy | P2 |
| CORS | GetBucketCors / PutBucketCors / DeleteBucketCors | P2 |
| Encryption | GetBucketEncryption / PutBucketEncryption / SSE-C | P2 |
| Tagging | GetBucketTagging / PutBucketTagging / Object Tagging | P2 |
| Object Lock | Legal Hold / Retention | P3 |
| Lifecycle | GetBucketLifecycle / PutBucketLifecycle | P3 |
| Logging | GetBucketLogging / PutBucketLogging | P3 |
| Presigned URL | GeneratePresignedUrl / 过期验证 | P2 |
| 存储类 | StorageClass 切换 / IA / Glacier | P3 |

---

*文档生成日期: 2026-05-14*
*基于 AWS S3 API 规范 (2024 版)、Ceph s3-tests、MinIO 兼容性测试实践*
