package com.amazon.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO representing the result of an explanation for a fraud prediction.
 *
 * Fields:
 * - eventId: the ID of the event being explained.
 * - explanation: a human-readable summary/explanation string.
 * - featureContributions: a map of feature name -> contribution score (e.g., SHAP value or similar).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExplanationResult {

    private String eventId;

    /**
     * A textual summary or explanation, e.g., "High amount and unusual IP contributed most."
     */
    private String explanation;

    /**
     * Map of feature name to its contribution score (positive/negative).
     * May be empty if no contributions are available.
     */
    private Map<String, Float> featureContributions;
}
