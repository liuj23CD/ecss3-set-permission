package com.example;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ECS S3 Bucket Permission Manager
 * 对bucket及其下的所有文件授予用户只读权限
 */
public class EcsS3Permission {

    // 环境变量配置
    private static final String ACCESS_KEY = System.getenv("ECS_S3_ACCESS_KEY");
    private static final String SECRET_KEY = System.getenv("ECS_S3_SECRET_KEY");
    private static final String ENDPOINT = System.getenv("ECS_S3_ENDPOINT");
    private static final String BUCKET_NAME = System.getenv("ECS_S3_BUCKET");

    // 目标用户
    private static final String TARGET_USER = System.getenv("ECS_S3_ACCESS_TARGET_USER");

    // 并行处理配置
    private static final int BATCH_SIZE = 1000;           // 每批处理对象数量
    private static final int THREAD_COUNT = 10;           // 并行线程数
    private static final String CHECKPOINT_FILE = "/tmp/ecs_permission_checkpoint.txt"; // 断点续传文件路径

    public static void main(String[] args) {
        System.out.println("=== ECS S3 Permission Manager ===");
        System.out.println("Bucket: " + BUCKET_NAME);
        System.out.println("Target User: " + TARGET_USER);
        System.out.println("Endpoint: " + ENDPOINT);
        System.out.println();

        // 创建S3客户端
        S3Client s3Client = createS3Client();

        try {
            // 1. 授予bucket级别的只读权限
            grantBucketReadOnlyPermission(s3Client);

            // 2. 授予bucket下所有对象的只读权限
            grantObjectReadOnlyPermission(s3Client);

            System.out.println("\n=== Permission grant completed successfully ===");

            // 输出最终总结
            System.out.println("\n=== Final Summary ===");
            System.out.println("Bucket: " + BUCKET_NAME);
            System.out.println("Target User: " + TARGET_USER);
            System.out.println("Bucket permissions: READ, READ_ACP");
            System.out.println("Object permissions: READ, READ_ACP (subject to object ownership)");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            s3Client.close();
        }
    }

    /**
     * 创建S3客户端配置
     */
    private static S3Client createS3Client() {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);

        S3Client s3Client = S3Client.builder()
                .region(Region.US_EAST_1)  // ECS S3不依赖具体区域
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .endpointOverride(URI.create(ENDPOINT))
                .build();

        return s3Client;
    }

    /**
     * 从断点文件读取已处理的对象列表
     */
    private static ConcurrentHashMap<String, Boolean> loadCheckpoint() {
        ConcurrentHashMap<String, Boolean> processedObjects = new ConcurrentHashMap<>();
        File checkpointFile = new File(CHECKPOINT_FILE);
        
        if (checkpointFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(checkpointFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        processedObjects.put(parts[0], Boolean.valueOf(parts[1]));
                    }
                }
                System.out.println("  - Loaded checkpoint: " + processedObjects.size() + " objects processed");
            } catch (Exception e) {
                System.out.println("  - Warning: Failed to load checkpoint file: " + e.getMessage());
            }
        }
        
        return processedObjects;
    }

    /**
     * 将已处理的对象保存到断点文件
     */
    private static void saveCheckpoint(ConcurrentHashMap<String, Boolean> processedObjects) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(CHECKPOINT_FILE))) {
            for (String key : processedObjects.keySet()) {
                writer.write(key + ":" + processedObjects.get(key));
                writer.newLine();
            }
        } catch (Exception e) {
            System.out.println("  - Warning: Failed to save checkpoint file: " + e.getMessage());
        }
    }

    /**
     * 清除断点文件
     */
    private static void clearCheckpoint() {
        File checkpointFile = new File(CHECKPOINT_FILE);
        if (checkpointFile.exists()) {
            checkpointFile.delete();
        }
    }

    /**
     * 授予bucket级别的只读权限
     */
    private static void grantBucketReadOnlyPermission(S3Client s3Client) {
        System.out.println("Granting bucket read permission to user: " + TARGET_USER);

        try {
            // 获取当前bucket的ACL
            GetBucketAclResponse aclResponse = s3Client.getBucketAcl(
                    GetBucketAclRequest.builder()
                            .bucket(BUCKET_NAME)
                            .build()
            );

            // 检查是否已授权
            List<Grant> grants = aclResponse.grants();
            boolean alreadyGranted = false;

            System.out.println("  - Current bucket ACL grants:");
            for (Grant grant : grants) {
                Grantee grantee = grant.grantee();
                String permission = grant.permission() != null ? grant.permission().toString() : "N/A";
                if (grantee != null) {
                    String granteeId = grantee.id() != null ? grantee.id() : "N/A";
                    String granteeName = grantee.displayName() != null ? grantee.displayName() : "N/A";
                    String granteeType = grantee.type() != null ? grantee.type().toString() : "N/A";
                    System.out.println("    - Grantee: " + granteeName + " (" + granteeId + "), Type: " + granteeType + ", Permission: " + permission);
                    if (grantee.id() != null && grantee.id().equals(TARGET_USER)) {
                        alreadyGranted = true;
                    }
                }
            }

            if (alreadyGranted) {
                System.out.println("  - Permission already granted to " + TARGET_USER);
                return;
            }

            // 构建新的ACL，添加只读权限
            List<Grant> newGrants = new ArrayList<>(grants);

            // 添加目标用户的只读权限和读取ACL权限
            Grantee newGrantee = Grantee.builder()
                    .id(TARGET_USER)
                    .type(Type.CANONICAL_USER)
                    .build();

            Grant readGrant = Grant.builder()
                    .grantee(newGrantee)
                    .permission(Permission.READ)
                    .build();

            Grant readAcpGrant = Grant.builder()
                    .grantee(newGrantee)
                    .permission(Permission.READ_ACP)
                    .build();

            newGrants.add(readGrant);
            newGrants.add(readAcpGrant);

            // 构建ACL对象
            AccessControlPolicy acl = AccessControlPolicy.builder()
                    .owner(aclResponse.owner())
                    .grants(newGrants)
                    .build();

            // 设置新的ACL
            PutBucketAclRequest putRequest = PutBucketAclRequest.builder()
                    .bucket(BUCKET_NAME)
                    .accessControlPolicy(acl)
                    .build();

            s3Client.putBucketAcl(putRequest);

            System.out.println("  - Successfully granted bucket read permission to " + TARGET_USER);

        } catch (Exception e) {
            System.out.println("  - Failed to grant bucket permission: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 授予bucket下所有对象的只读权限（优化版：流式处理+并行处理+断点续传）
     */
    private static void grantObjectReadOnlyPermission(S3Client s3Client) {
        System.out.println("\nGranting object read permissions to user: " + TARGET_USER);

        // 加载断点
        ConcurrentHashMap<String, Boolean> processedObjects = loadCheckpoint();
        
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(1);
        
        try {
            // 列出bucket中的所有对象（流式处理，不加载全部到内存）
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(BUCKET_NAME)
                    .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            
            // 统计信息
            AtomicInteger totalObjects = new AtomicInteger(0);
            AtomicInteger processed = new AtomicInteger(processedObjects.size());
            AtomicInteger skipped = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);

            // 处理当前页
            processObjectsBatch(s3Client, listResponse.contents(), executor, latch, processedObjects, 
                processed, skipped, failed, totalObjects);

            // 处理分页（流式处理，逐页处理）
            while (listResponse.isTruncated()) {
                ListObjectsV2Request listRequestNext = ListObjectsV2Request.builder()
                        .bucket(BUCKET_NAME)
                        .continuationToken(listResponse.nextContinuationToken())
                        .build();

                listResponse = s3Client.listObjectsV2(listRequestNext);
                
                processObjectsBatch(s3Client, listResponse.contents(), executor, latch, processedObjects,
                    processed, skipped, failed, totalObjects);

                // 每处理10万条输出一次进度
                if (totalObjects.get() % 100000 == 0) {
                    System.out.println("  - Progress: processed " + totalObjects.get() + " objects, " + 
                        processed.get() + " granted, " + skipped.get() + " skipped, " + failed.get() + " failed");
                    // 保存断点
                    saveCheckpoint(processedObjects);
                }
            }

            // 等待所有任务完成
            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);

            // 保存最终断点
            saveCheckpoint(processedObjects);

            System.out.println("  - Processing complete: " + processed.get() + " granted, " + skipped.get() + " skipped, " + failed.get() + " failed");

            // 输出成功和失败清单
            System.out.println("\n=== Permission Grant Summary ===");
            System.out.println("Total objects processed: " + totalObjects.get());
            System.out.println("Successfully granted: " + processed.get());
            System.out.println("Skipped (already granted): " + skipped.get());
            System.out.println("Failed: " + failed.get());

            // 清除断点文件
            clearCheckpoint();

        } catch (Exception e) {
            System.out.println("  - Failed to list objects: " + e.getMessage());
            e.printStackTrace();
            // 出错时保存断点
            saveCheckpoint(processedObjects);
            throw new RuntimeException(e);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 批量处理对象（支持并行处理和断点续传）
     */
    private static void processObjectsBatch(S3Client s3Client, List<S3Object> objects, 
            ExecutorService executor, CountDownLatch latch,
            ConcurrentHashMap<String, Boolean> processedObjects,
            AtomicInteger processed, AtomicInteger skipped, AtomicInteger failed, AtomicInteger total) {
        
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (S3Object object : objects) {
            String objectKey = object.key();
            total.incrementAndGet();
            
            // 检查是否已处理过
            if (processedObjects.containsKey(objectKey)) {
                Boolean success = processedObjects.get(objectKey);
                if (success) {
                    skipped.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
                continue;
            }
            
            // 提交任务到线程池
            Future<Boolean> future = executor.submit(() -> {
                try {
                    boolean result = grantObjectReadPermission(s3Client, objectKey);
                    processedObjects.put(objectKey, result);
                    if (result) {
                        processed.incrementAndGet();
                    } else {
                        skipped.incrementAndGet();
                    }
                    return result;
                } catch (Exception e) {
                    processedObjects.put(objectKey, false);
                    failed.incrementAndGet();
                    return false;
                }
            });
            
            futures.add(future);
            
            // 控制批处理大小，避免内存溢出
            if (futures.size() >= BATCH_SIZE) {
                waitForFutures(futures);
                futures.clear();
            }
        }
        
        // 处理剩余的future
        waitForFutures(futures);
    }

    /**
     * 等待一批future完成
     */
    private static void waitForFutures(List<Future<Boolean>> futures) {
        for (Future<Boolean> future : futures) {
            try {
                future.get(5, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                System.out.println("  - Warning: Task timeout");
                future.cancel(true);
            } catch (Exception e) {
                System.out.println("  - Warning: Task failed: " + e.getMessage());
            }
        }
    }

    /**
     * 授予单个对象的只读权限
     * @return true if permission was granted, false if already granted or failed
     */
    private static boolean grantObjectReadPermission(S3Client s3Client, String objectKey) {
        try {
            // 获取对象当前ACL
            GetObjectAclResponse aclResponse = s3Client.getObjectAcl(
                    GetObjectAclRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(objectKey)
                            .build()
            );

            // 检查是否已授权（同时检查READ和READ_ACP权限）
            List<Grant> grants = aclResponse.grants();
            boolean hasReadPermission = false;
            boolean hasReadAcpPermission = false;

            for (Grant grant : grants) {
                Grantee grantee = grant.grantee();
                if (grantee != null && grantee.id() != null && grantee.id().equals(TARGET_USER)) {
                    String permission = grant.permission() != null ? grant.permission().toString() : "";
                    if ("READ".equals(permission)) {
                        hasReadPermission = true;
                    } else if ("READ_ACP".equals(permission)) {
                        hasReadAcpPermission = true;
                    }
                }
            }

            // 只有同时拥有READ和READ_ACP权限时才跳过
            if (hasReadPermission && hasReadAcpPermission) {
                System.out.println("  - [SKIPPED - Already granted] " + objectKey);
                return false;
            }

            // 构建新的ACL
            List<Grant> newGrants = new ArrayList<>(grants);

            Grantee newGrantee = Grantee.builder()
                    .id(TARGET_USER)
                    .type(Type.CANONICAL_USER)
                    .build();

            Grant readGrant = Grant.builder()
                    .grantee(newGrantee)
                    .permission(Permission.READ)
                    .build();

            Grant readAcpGrant = Grant.builder()
                    .grantee(newGrantee)
                    .permission(Permission.READ_ACP)
                    .build();

            newGrants.add(readGrant);
            newGrants.add(readAcpGrant);

            AccessControlPolicy acl = AccessControlPolicy.builder()
                    .owner(aclResponse.owner())
                    .grants(newGrants)
                    .build();

            PutObjectAclRequest putRequest = PutObjectAclRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(objectKey)
                    .accessControlPolicy(acl)
                    .build();

            s3Client.putObjectAcl(putRequest);

            System.out.println("  - [SUCCESS] " + objectKey);
            return true;

        } catch (Exception e) {
            System.out.println("  - [FAILED] " + objectKey + " - Error: " + e.getMessage());
            return false;
        }
    }
}
