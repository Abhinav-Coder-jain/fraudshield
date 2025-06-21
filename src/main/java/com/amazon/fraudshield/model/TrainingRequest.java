package com.amazon.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO for initiating a SageMaker training job and subsequent Fraud Detector model version registration.
 *
 * Fields:
 * - realDataS3Path: S3 URI for real historical data (must start with "s3://").
 * - syntheticDataS3Path: S3 URI for synthetic fraud data (must start with "s3://").
 * - modelName: name for the SageMaker training job/model (non-blank).
 * - instanceType: optional SageMaker instance type (e.g., "ml.m5.xlarge"); if null or blank, service uses default.
 * - algorithmName: optional algorithm name (e.g., built-in "xgboost"); if null, service may use default container or custom image.
 * - instanceCount: optional number of instances (>=1); if null, default is 1.
 * - volumeSizeInGB: optional EBS volume size in GB (>=5); if null, default is 20.
 * - maxRuntimeInSeconds: optional max runtime in seconds (>=60); if null, default is 3600.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrainingRequest {

    @NotBlank(message = "S3 path for real data cannot be blank")
    @Pattern(regexp = "^s3://.+", message = "realDataS3Path must start with s3://")
    private String realDataS3Path;

    @NotBlank(message = "S3 path for synthetic data cannot be blank")
    @Pattern(regexp = "^s3://.+", message = "syntheticDataS3Path must start with s3://")
    private String syntheticDataS3Path;

    @NotBlank(message = "SageMaker model name cannot be blank")
    private String modelName;

    /**
     * Optional SageMaker instance type (e.g., "ml.m5.xlarge").
     * If null or blank, the service will apply a default value.
     */
    private String instanceType;

    /**
     * Optional algorithm name (e.g., "xgboost") if using built-in SageMaker algorithms.
     * If null or blank, the service may use a default training image or custom container.
     */
    private String algorithmName;

    /**
     * Optional number of instances for training. If null, default is 1.
     */
    @Min(value = 1, message = "instanceCount must be >= 1")
    private Integer instanceCount;

    /**
     * Optional EBS volume size in GB for training. If null, default is 20.
     */
    @Min(value = 5, message = "volumeSizeInGB must be >= 5")
    private Integer volumeSizeInGB;

    /**
     * Optional maximum runtime in seconds for the SageMaker job. If null, default is 3600.
     */
    @Min(value = 60, message = "maxRuntimeInSeconds must be >= 60")
    private Integer maxRuntimeInSeconds;
}
