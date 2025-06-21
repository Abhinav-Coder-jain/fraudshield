package com.amazon.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO for requesting synthetic fraud data generation (e.g., via Bedrock).
 *
 * Fields:
 * - fraudType: type of fraud to simulate (e.g., "synthetic_identity", "credit_card_fraud").
 * - count: number of synthetic fraud records to generate (must be >= 1).
 * - additionalDetails: optional free-text guiding the generation prompt (e.g., "focus on transactions over $1000").
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerationRequest {

    @NotBlank(message = "Fraud type cannot be blank")
    private String fraudType;

    @Min(value = 1, message = "Count must be at least 1")
    private int count;

    private String additionalDetails;
}
