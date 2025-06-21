package com.amazon.fraudshield.service;

import com.amazon.fraudshield.model.PredictionResult;
import com.amazon.fraudshield.model.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.frauddetector.FraudDetectorClient;
import software.amazon.awssdk.services.frauddetector.model.GetEventPredictionRequest;
import software.amazon.awssdk.services.frauddetector.model.GetEventPredictionResponse;
import software.amazon.awssdk.services.frauddetector.model.ModelScores;
import software.amazon.awssdk.services.frauddetector.model.RuleResult;
import software.amazon.awssdk.services.frauddetector.model.ModelVersion; // NEW: Import ModelVersion
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointResponse;
import software.amazon.awssdk.core.SdkBytes;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class FraudDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionService.class);

    private final FraudDetectorClient fraudDetectorClient;
    private final SageMakerRuntimeClient sageMakerRuntimeClient; // Optional: for behavioral biometrics

    @Value("${app.fraud-detector.detector-id}")
    private String fraudDetectorId;

    @Value("${app.fraud-detector.event-type-name}")
    private String fraudDetectorEventTypeName;

    // This field holds the endpoint name, but is NOT directly injected via @Value on the field itself
    // to allow optional injection via constructor without crashing if property is missing.
    private String behavioralBiometricsEndpointName;

    // Formatter for eventTimestamp consistent with Fraud Detector (ISO 8601)
    private static final DateTimeFormatter ISO_8601_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);


    public FraudDetectionService(FraudDetectorClient fraudDetectorClient,
                                 // @Value("${app.sagemaker.clarify-endpoint-name:}") is here to make it optional
                                 @Value("${app.sagemaker.clarify-endpoint-name:}") String behavioralBiometricsEndpointName,
                                 SageMakerRuntimeClient sageMakerRuntimeClient) {
        this.fraudDetectorClient = fraudDetectorClient;
        this.behavioralBiometricsEndpointName = behavioralBiometricsEndpointName;
        this.sageMakerRuntimeClient = sageMakerRuntimeClient;
    }

    /**
     * Provides real-time fraud predictions for incoming transactions.
     * This method orchestrates calls to Amazon Fraud Detector and optionally
     * to a SageMaker endpoint for behavioral biometrics.
     *
     * @param event The TransactionEvent object containing event details.
     * @return A PredictionResult DTO containing the fraud prediction.
     */
    public PredictionResult getFraudPrediction(TransactionEvent event) {
        logger.info("Getting fraud prediction for eventId: {}", event.getEventId());

        try {
            // 1. Optionally invoke SageMaker behavioral biometrics endpoint
            // This entire block is now removed/commented because TransactionEvent no longer has getBehavioralData().
            // If you re-introduce behavioral biometrics, ensure TransactionEvent has the data and
            // Fraud Detector Event Type has the corresponding variable.
            Double behavioralScore = null;
            /*
            // Original behavioral biometrics logic - currently commented out due to TransactionEvent change
            if (behavioralBiometricsEndpointName != null && !behavioralBiometricsEndpointName.isEmpty() && event.getBehavioralData() != null && !event.getBehavioralData().isEmpty()) {
                try {
                    String behavioralDataJson = event.getBehavioralData();
                    InvokeEndpointRequest invokeEndpointRequest = InvokeEndpointRequest.builder()
                            .endpointName(behavioralBiometricsEndpointName)
                            .contentType("application/json")
                            .body(SdkBytes.fromUtf8String(behavioralDataJson))
                            .build();

                    InvokeEndpointResponse invokeEndpointResponse = sageMakerRuntimeClient.invokeEndpoint(invokeEndpointRequest);
                    String responseBody = invokeEndpointResponse.body().asUtf8String();
                    try {
                        behavioralScore = Double.parseDouble(
                                responseBody.replace("{\"score\":", "").replace("}", "")
                        );
                        logger.info("Behavioral biometrics score for event {}: {}", event.getEventId(), behavioralScore);
                    } catch (NumberFormatException nfe) {
                        logger.warn("Could not parse behavioral score from SageMaker response for event {}: responseBody={}", event.getEventId(), responseBody);
                    }
                } catch (Exception e) {
                    logger.warn("Could not invoke SageMaker behavioral biometrics endpoint for event {}: {}", event.getEventId(), e.getMessage());
                }
            }
            */

            // 2. Prepare event variables for Amazon Fraud Detector
            // These variable names MUST match the variable names in your Fraud Detector Event Type
            Map<String, String> eventVariables = new HashMap<>();

            // Mapping from TransactionEvent (camelCase) to Fraud Detector Event Type (snake_case)
            if (event.getEventTimestamp() != null) {
                eventVariables.put("event_timestamp", event.getEventTimestamp().atZone(ZoneOffset.UTC).format(ISO_8601_FORMATTER));
            } else {
                // Provide a fallback or log a warning if timestamp is null
                eventVariables.put("event_timestamp", Instant.now().atZone(ZoneOffset.UTC).format(ISO_8601_FORMATTER));
                logger.warn("TransactionEvent eventTimestamp was null for eventId: {}. Using current time as fallback.", event.getEventId());
            }

            if (event.getPurchaseAmount() != null) {
                eventVariables.put("purchase_amount", String.valueOf(event.getPurchaseAmount()));
            }
            if (event.getIpAddress() != null) {
                eventVariables.put("ip_address", event.getIpAddress());
            }
            if (event.getDeviceId() != null) {
                eventVariables.put("device_id", event.getDeviceId());
            }
            if (event.getPaymentMethod() != null) {
                eventVariables.put("payment_method", event.getPaymentMethod());
            }
            // Removed references to email_address, user_agent, behavioral_score
            // as they are no longer in TransactionEvent or explicitly configured as variables.


            // 3. Call Amazon Fraud Detector to get event prediction
            GetEventPredictionRequest getEventPredictionRequest = GetEventPredictionRequest.builder()
                    .detectorId(fraudDetectorId)
                    .eventId(event.getEventId())
                    .eventTypeName(fraudDetectorEventTypeName)
                    .eventVariables(eventVariables)
                    // Entities: assuming single entity, map to Fraud Detector entity type and ID
                    .entities(e -> e.entityType(event.getEntityType()).entityId(event.getEntityId()))
                    .build();

            GetEventPredictionResponse predictionResponse =
                    fraudDetectorClient.getEventPrediction(getEventPredictionRequest);

            // 4. Process response and map to PredictionResult DTO
            PredictionResult result = new PredictionResult();
            result.setEventId(event.getEventId());
            result.setDetectorId(fraudDetectorId);

            // Extract detectorVersionId from response
            Optional<String> detVerOpt = predictionResponse.getValueForField("detectorVersionId", String.class);
            detVerOpt.ifPresent(result::setDetectorVersionId);

            // 4a. Extract model scores
            List<ModelScores> modelScoresList = predictionResponse.modelScores();
            if (modelScoresList != null && !modelScoresList.isEmpty()) {
                Map<String, Float> scores = new HashMap<>();
                for (ModelScores ms : modelScoresList) {
                    if (ms.hasScores()) {
                        String modelId = null;
                        String modelVersionNumber = null;
                        try {
                            if (ms.modelVersion() != null) {
                                // Accessing methods on ModelVersion
                                ModelVersion mv = ms.modelVersion();
                                modelId = mv.modelId();
                                modelVersionNumber = mv.modelVersionNumber();
                            }
                        } catch (Exception e) {
                            logger.warn("Could not extract modelId/modelVersionNumber from ModelScores.modelVersion(): {}", e.getMessage());
                        }
                        for (Map.Entry<String, Float> entry : ms.scores().entrySet()) {
                            String varName = entry.getKey();
                            Float value = entry.getValue();
                            String key;
                            if (modelId != null && modelVersionNumber != null) {
                                key = modelId + ":" + modelVersionNumber + "_" + varName;
                            } else if (modelId != null) {
                                key = modelId + "_" + varName;
                            } else {
                                key = varName;
                            }
                            scores.put(key, value);
                        }
                    }
                }
                result.setModelScores(scores);
            }

            // 4b. Extract rule results and outcomes
            List<RuleResult> ruleResults = predictionResponse.ruleResults();
            if (ruleResults != null && !ruleResults.isEmpty()) {
                List<String> matchedRuleIds = ruleResults.stream()
                        .map(RuleResult::ruleId)
                        .collect(Collectors.toList());
                result.setRuleResults(matchedRuleIds);

                List<String> allOutcomes = ruleResults.stream()
                        .filter(rr -> rr.hasOutcomes())
                        .flatMap(rr -> rr.outcomes().stream())
                        .distinct()
                        .collect(Collectors.toList());
                result.setOutcomes(allOutcomes);
            }

            logger.info("Fraud prediction for event {}: RuleResults={}, Outcomes={}, ModelScores={}",
                    event.getEventId(),
                    result.getRuleResults(),
                    result.getOutcomes(),
                    result.getModelScores());

            return result;

        } catch (Exception e) {
            logger.error("Error getting fraud prediction for event {}: {}", event.getEventId(), e.getMessage(), e);
            throw new RuntimeException("Failed to get fraud prediction: " + e.getMessage(), e);
        }
    }
}
