package com.amazon.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO representing the result of a fraud prediction.
 *
 * Fields:
 * - eventId: the ID of the event for which prediction was requested.
 * - detectorId: the Fraud Detector detector ID used.
 * - detectorVersionId: the version of the detector used (may be null if not captured).
 * - modelScores: map of model-specific score metrics, e.g., "modelId_scoreType" -> value.
 * - ruleResults: list of matched rule IDs.
 * - outcomes: list of final outcome IDs (e.g., "approve", "review", "reject"), aggregated from ruleResults.
 *
 * You can extend with additional fields (e.g., overallScore, timestamp, rawResponse) as needed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PredictionResult {

    private String eventId;
    private String detectorId;
    private String detectorVersionId;
    private Map<String, Float> modelScores;
    private List<String> ruleResults;
    private List<String> outcomes;
}
