/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.repositories.s3;

import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.StorageClass;

import org.apache.logging.log4j.LogManager;
import org.opensearch.cluster.metadata.RepositoryMetadata;
import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.BlobStore;
import org.opensearch.common.blobstore.BlobStoreException;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.repositories.s3.async.AsyncExecutorContainer;
import org.opensearch.repositories.s3.async.AsyncTransferManager;
import org.opensearch.repositories.s3.async.SizeBasedBlockingQ;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.opensearch.repositories.s3.S3Repository.BUCKET_SETTING;
import static org.opensearch.repositories.s3.S3Repository.BUFFER_SIZE_SETTING;
import static org.opensearch.repositories.s3.S3Repository.BULK_DELETE_SIZE;
import static org.opensearch.repositories.s3.S3Repository.CANNED_ACL_SETTING;
import static org.opensearch.repositories.s3.S3Repository.EXPECTED_BUCKET_OWNER_SETTING;
import static org.opensearch.repositories.s3.S3Repository.PERMIT_BACKED_TRANSFER_ENABLED;
import static org.opensearch.repositories.s3.S3Repository.REDIRECT_LARGE_S3_UPLOAD;
import static org.opensearch.repositories.s3.S3Repository.SERVER_SIDE_ENCRYPTION_BUCKET_KEY_SETTING;
import static org.opensearch.repositories.s3.S3Repository.SERVER_SIDE_ENCRYPTION_ENCRYPTION_CONTEXT_SETTING;
import static org.opensearch.repositories.s3.S3Repository.SERVER_SIDE_ENCRYPTION_KMS_KEY_SETTING;
import static org.opensearch.repositories.s3.S3Repository.SERVER_SIDE_ENCRYPTION_TYPE_SETTING;
import static org.opensearch.repositories.s3.S3Repository.STORAGE_CLASS_SETTING;
import static org.opensearch.repositories.s3.S3Repository.UPLOAD_RETRY_ENABLED;

public class S3BlobStore implements BlobStore {

    private final S3Service service;

    private final S3AsyncService s3AsyncService;

    private volatile String bucket;

    private volatile ByteSizeValue bufferSize;

    private volatile boolean redirectLargeUploads;

    private volatile boolean uploadRetryEnabled;

    private volatile boolean permitBackedTransferEnabled;

    private volatile String serverSideEncryptionType;
    private volatile String serverSideEncryptionKmsKey;
    private volatile boolean serverSideEncryptionBucketKey;
    private volatile String serverSideEncryptionEncryptionContext;
    private volatile String expectedBucketOwner;

    private volatile ObjectCannedACL cannedACL;

    private volatile StorageClass storageClass;

    private volatile int bulkDeletesSize;

    private volatile RepositoryMetadata repositoryMetadata;

    private final StatsMetricPublisher statsMetricPublisher = new StatsMetricPublisher();

    private final AsyncTransferManager asyncTransferManager;
    private final AsyncExecutorContainer urgentExecutorBuilder;
    private final AsyncExecutorContainer priorityExecutorBuilder;
    private final AsyncExecutorContainer normalExecutorBuilder;
    private final boolean multipartUploadEnabled;
    private final SizeBasedBlockingQ normalPrioritySizeBasedBlockingQ;
    private final SizeBasedBlockingQ lowPrioritySizeBasedBlockingQ;
    private final GenericStatsMetricPublisher genericStatsMetricPublisher;

    S3BlobStore(
        S3Service service,
        S3AsyncService s3AsyncService,
        boolean multipartUploadEnabled,
        String bucket,
        ByteSizeValue bufferSize,
        String cannedACL,
        String storageClass,
        int bulkDeletesSize,
        RepositoryMetadata repositoryMetadata,
        AsyncTransferManager asyncTransferManager,
        AsyncExecutorContainer urgentExecutorBuilder,
        AsyncExecutorContainer priorityExecutorBuilder,
        AsyncExecutorContainer normalExecutorBuilder,
        SizeBasedBlockingQ normalPrioritySizeBasedBlockingQ,
        SizeBasedBlockingQ lowPrioritySizeBasedBlockingQ,
        GenericStatsMetricPublisher genericStatsMetricPublisher,
        String serverSideEncryptionType,
        String serverSideEncryptionKmsKey,
        boolean serverSideEncryptionBucketKey,
        String serverSideEncryptionEncryptionContext,
        String expectedBucketOwner
    ) {
        this.service = service;
        this.s3AsyncService = s3AsyncService;
        this.multipartUploadEnabled = multipartUploadEnabled;
        this.bucket = bucket;
        this.bufferSize = bufferSize;
        this.cannedACL = initCannedACL(cannedACL);
        this.storageClass = initStorageClass(storageClass);
        this.bulkDeletesSize = bulkDeletesSize;
        this.repositoryMetadata = repositoryMetadata;
        this.asyncTransferManager = asyncTransferManager;
        this.normalExecutorBuilder = normalExecutorBuilder;
        this.priorityExecutorBuilder = priorityExecutorBuilder;
        this.urgentExecutorBuilder = urgentExecutorBuilder;
        // Settings to initialize blobstore with.
        this.redirectLargeUploads = REDIRECT_LARGE_S3_UPLOAD.get(repositoryMetadata.settings());
        this.uploadRetryEnabled = UPLOAD_RETRY_ENABLED.get(repositoryMetadata.settings());
        this.normalPrioritySizeBasedBlockingQ = normalPrioritySizeBasedBlockingQ;
        this.lowPrioritySizeBasedBlockingQ = lowPrioritySizeBasedBlockingQ;
        this.genericStatsMetricPublisher = genericStatsMetricPublisher;
        this.permitBackedTransferEnabled = PERMIT_BACKED_TRANSFER_ENABLED.get(repositoryMetadata.settings());
        this.serverSideEncryptionType = serverSideEncryptionType;
        this.serverSideEncryptionKmsKey = serverSideEncryptionKmsKey;
        this.serverSideEncryptionBucketKey = serverSideEncryptionBucketKey;
        this.serverSideEncryptionEncryptionContext = serverSideEncryptionEncryptionContext;
        this.expectedBucketOwner = expectedBucketOwner;
    }

    @Override
    public void reload(RepositoryMetadata repositoryMetadata) {
        this.repositoryMetadata = repositoryMetadata;
        this.bucket = BUCKET_SETTING.get(repositoryMetadata.settings());
        this.bufferSize = BUFFER_SIZE_SETTING.get(repositoryMetadata.settings());
        this.cannedACL = initCannedACL(CANNED_ACL_SETTING.get(repositoryMetadata.settings()));
        this.storageClass = initStorageClass(STORAGE_CLASS_SETTING.get(repositoryMetadata.settings()));
        this.bulkDeletesSize = BULK_DELETE_SIZE.get(repositoryMetadata.settings());
        this.redirectLargeUploads = REDIRECT_LARGE_S3_UPLOAD.get(repositoryMetadata.settings());
        this.uploadRetryEnabled = UPLOAD_RETRY_ENABLED.get(repositoryMetadata.settings());
        this.permitBackedTransferEnabled = PERMIT_BACKED_TRANSFER_ENABLED.get(repositoryMetadata.settings());
        this.serverSideEncryptionType = SERVER_SIDE_ENCRYPTION_TYPE_SETTING.get(repositoryMetadata.settings());
        this.serverSideEncryptionKmsKey = SERVER_SIDE_ENCRYPTION_KMS_KEY_SETTING.get(repositoryMetadata.settings());
        this.serverSideEncryptionBucketKey = SERVER_SIDE_ENCRYPTION_BUCKET_KEY_SETTING.get(repositoryMetadata.settings());
        this.serverSideEncryptionEncryptionContext = SERVER_SIDE_ENCRYPTION_ENCRYPTION_CONTEXT_SETTING.get(repositoryMetadata.settings());
        this.expectedBucketOwner = EXPECTED_BUCKET_OWNER_SETTING.get(repositoryMetadata.settings());
    }

    @Override
    public String toString() {
        return bucket;
    }

    public AmazonS3Reference clientReference() {
        return service.client(repositoryMetadata);
    }

    public AmazonAsyncS3Reference asyncClientReference() {
        return s3AsyncService.client(repositoryMetadata, urgentExecutorBuilder, priorityExecutorBuilder, normalExecutorBuilder);
    }

    int getMaxRetries() {
        return service.settings(repositoryMetadata).maxRetries;
    }

    public boolean isRedirectLargeUploads() {
        return redirectLargeUploads;
    }

    public boolean isUploadRetryEnabled() {
        return uploadRetryEnabled;
    }

    public boolean isPermitBackedTransferEnabled() {
        return permitBackedTransferEnabled;
    }

    public String bucket() {
        return bucket;
    }

    public String serverSideEncryptionType() {
        return serverSideEncryptionType;
    }

    public String serverSideEncryptionKmsKey() {
        return serverSideEncryptionKmsKey;
    }

    public boolean serverSideEncryptionBucketKey() {
        return serverSideEncryptionBucketKey;
    }

    /**
     * Returns the SSE encryption context base64 UTF8 encoded, as required by S3 SDK. If the encryption context is empty return
     * null as the S3 client ignores null header values
     */
    public String serverSideEncryptionEncryptionContext() {
        return serverSideEncryptionEncryptionContext.isEmpty()
            ? null
            : Base64.getEncoder().encodeToString(serverSideEncryptionEncryptionContext.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the expected bucket owner if set, else null as the S3 client ignores null header values
     */
    public String expectedBucketOwner() {
        return expectedBucketOwner.isEmpty() ? null : expectedBucketOwner;
    }

    public long bufferSizeInBytes() {
        return bufferSize.getBytes();
    }

    public int getBulkDeletesSize() {
        return bulkDeletesSize;
    }

    public SizeBasedBlockingQ getNormalPrioritySizeBasedBlockingQ() {
        return normalPrioritySizeBasedBlockingQ;
    }

    public SizeBasedBlockingQ getLowPrioritySizeBasedBlockingQ() {
        return lowPrioritySizeBasedBlockingQ;
    }

    @Override
    public BlobContainer blobContainer(BlobPath path) {
        return new S3BlobContainer(path, this);
    }

    @Override
    public void close() throws IOException {
        if (service != null) {
            this.service.close();
        }
        if (s3AsyncService != null) {
            this.s3AsyncService.close();
        }
    }

    @Override
    public Map<String, Long> stats() {
        Map<String, Long> stats = statsMetricPublisher.getStats().toMap();
        stats.putAll(genericStatsMetricPublisher.stats());
        return stats;
    }

    @Override
    public Map<Metric, Map<String, Long>> extendedStats() {
        if (statsMetricPublisher.getExtendedStats() == null || statsMetricPublisher.getExtendedStats().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Metric, Map<String, Long>> extendedStats = new HashMap<>();
        statsMetricPublisher.getExtendedStats().forEach((k, v) -> extendedStats.put(k, v.toMap()));
        extendedStats.put(Metric.GENERIC_STATS, genericStatsMetricPublisher.stats());
        return extendedStats;
    }

    @Override
    public boolean isBlobMetadataEnabled() {
        return true;
    }

    public ObjectCannedACL getCannedACL() {
        return cannedACL;
    }

    public StorageClass getStorageClass() {
        return storageClass;
    }

    public StatsMetricPublisher getStatsMetricPublisher() {
        return statsMetricPublisher;
    }

    public static StorageClass initStorageClass(String storageClassStringValue) {
        if ((storageClassStringValue == null) || storageClassStringValue.equals("")) {
            return StorageClass.STANDARD;
        }

        final StorageClass storageClass = StorageClass.fromValue(storageClassStringValue.toUpperCase(Locale.ENGLISH));
        if (storageClass.equals(StorageClass.GLACIER)) {
            throw new BlobStoreException("Glacier storage class is not supported");
        }

        if (storageClass == StorageClass.UNKNOWN_TO_SDK_VERSION) {
            throw new BlobStoreException("`" + storageClassStringValue + "` is not a valid S3 Storage Class.");
        }

        return storageClass;
    }

    /**
     * Constructs canned acl from string
     */
    public static ObjectCannedACL initCannedACL(String cannedACL) {
        if ((cannedACL == null) || cannedACL.equals("")) {
            return ObjectCannedACL.PRIVATE;
        }

        for (final ObjectCannedACL cur : ObjectCannedACL.values()) {
            if (cur.toString().equalsIgnoreCase(cannedACL)) {
                return cur;
            }
        }

        throw new BlobStoreException("cannedACL is not valid: [" + cannedACL + "]");
    }

    public AsyncTransferManager getAsyncTransferManager() {
        return asyncTransferManager;
    }
}
