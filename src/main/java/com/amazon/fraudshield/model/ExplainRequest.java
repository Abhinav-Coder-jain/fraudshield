package com.amazon.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * DTO for requesting an explanation (e.g., via a SageMaker Clarify endpoint)
 * for a fraud prediction that was previously made.
 *
 * Fields:
 * - eventId: the unique ID of the event to explain.
 * - detectorId: the Fraud Detector detector ID used in the prediction.
 * - detectorVersionId: the version ID of the detector used.
 * - eventVariables: the map of feature names to values used when making the prediction;
 *   needed by the explanation endpoint to recompute contributions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExplainRequest {

    @NotBlank(message = "Event ID cannot be blank")
    private String eventId;

    @NotBlank(message = "Detector ID cannot be blank")
    private String detectorId;

    @NotBlank(message = "Detector Version ID cannot be blank")
    private String detectorVersionId;

    /**
     * Original event variables used in the prediction, as a map of variable name -> value.
     * May be null or empty if not available; explanation logic should handle that case.
     */
    private Map<String, String> eventVariables;
}
