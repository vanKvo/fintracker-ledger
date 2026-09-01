package com.fintracker.ledger.statement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Generates presigned S3 PUT URLs for direct-from-browser statement uploads
 * (CLAUDE.md: "PDF uploads go directly to S3 (pre-signed URL)").
 *
 * The object metadata baked into the presigned request (user-id, statement-id,
 * account-id, bank-id) becomes part of what S3 validates on the actual PUT —
 * the client's upload request MUST send the exact same x-amz-meta-* headers
 * or S3 rejects it with a signature mismatch. This is deliberate: it's what
 * lets the data-pipeline's S3 trigger (orchestrator.py's s3_processor_handler)
 * read a verified, tamper-evident identity off the uploaded object itself,
 * rather than trusting a value the client could otherwise set to anything
 * (REQ-DP-05 "Verify Tenant Identity at Ingestion").
 */
@Component
public class S3PresignService {

    private final S3Presigner presigner;
    private final String bucketName;
    private final Duration expiry;

    public S3PresignService(
            @Value("${aws.s3.statements-bucket}") String bucketName,
            @Value("${aws.s3.presigned-url-expiry-minutes}") long expiryMinutes,
            @Value("${aws.region}") String region
    ) {
        this.bucketName = bucketName;
        this.expiry = Duration.ofMinutes(expiryMinutes);
        this.presigner = S3Presigner.builder()
                .region(software.amazon.awssdk.regions.Region.of(region))
                .build();
    }

    /**
     * @return a presigned URL valid for {@code aws.s3.presigned-url-expiry-minutes},
     *         plus the S3 object key the client must PUT to.
     */
    public PresignedUpload presignStatementUpload(UUID userId, UUID statementId, UUID accountId,
                                                    String bankId, String fileName) {
        String s3ObjectKey = "statements/%s/%s/%s".formatted(userId, statementId, sanitizeFileName(fileName));

        var metadata = bankId != null
                ? Map.of("user-id", userId.toString(), "statement-id", statementId.toString(),
                         "account-id", accountId.toString(), "bank-id", bankId)
                : Map.of("user-id", userId.toString(), "statement-id", statementId.toString(),
                         "account-id", accountId.toString());

        var putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3ObjectKey)
                .metadata(metadata)
                .build();

        var presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(putRequest)
                .build();

        var presigned = presigner.presignPutObject(presignRequest);
        return new PresignedUpload(presigned.url().toString(), s3ObjectKey);
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload";
        }
        // Strip anything that isn't safe in an S3 key / doesn't need to be there
        // (path separators, whitespace) — the original name is cosmetic only,
        // never parsed for anything security- or logic-relevant downstream.
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record PresignedUpload(String url, String s3ObjectKey) {}
}
