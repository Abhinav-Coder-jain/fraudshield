package com.amazon.fraudshield.service;

import com.amazon.fraudshield.model.ExplainRequest;
import com.amazon.fraudshield.model.ExplanationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExplainabilityService {

    private static final Logger logger = LoggerFactory.getLogger(ExplainabilityService.class);
    private final SageMakerRuntimeClient sageMakerRuntimeClient;
    private final ObjectMapper objectMapper; // For JSON processing

    @Value("${app.sagemaker.clarify-endpoint-name:}")
    private String clarifyEndpointName;

    public ExplainabilityService(SageMakerRuntimeClient sageMakerRuntimeClient, ObjectMapper objectMapper) {
        this.sageMakerRuntimeClient = sageMakerRuntimeClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Provides explanations for why a transaction was flagged, typically by invoking
     * a SageMaker Clarify endpoint.
     *
     * @param explainRequest The ExplainRequest DTO containing details needed for explanation.
     * @return An ExplanationResult DTO with the explanation data.
     */
    public ExplanationResult getExplanation(ExplainRequest explainRequest) {
        logger.info("Getting explanation for eventId: {}", explainRequest.getEventId());

        if (clarifyEndpointName == null || clarifyEndpointName.isEmpty()) {
            logger.warn("SageMaker Clarify endpoint name is not configured. Cannot provide explanation for eventId: {}", explainRequest.getEventId());
            return ExplanationResult.builder()
                    .eventId(explainRequest.getEventId())
                    .explanation("SageMaker Clarify endpoint is not configured. Cannot provide detailed explanation.")
                    .featureContributions(Collections.emptyMap())
                    .build();
        }

        try {
            // Construct the input JSON for the Clarify endpoint.
            // This structure depends entirely on how your Clarify endpoint is configured.
            // Example: A simple JSON containing the original event variables.
            ObjectNode inputNode = objectMapper.createObjectNode();
            inputNode.put("event_id", explainRequest.getEventId());
            inputNode.put("detector_id", explainRequest.getDetectorId());
            inputNode.put("detector_version_id", explainRequest.getDetectorVersionId());

            // Add original event variables if available in the explainRequest
            if (explainRequest.getEventVariables() != null && !explainRequest.getEventVariables().isEmpty()) {
                ObjectNode variablesNode = inputNode.putObject("event_variables");
                explainRequest.getEventVariables().forEach(variablesNode::put);
            }

            // Convert JsonNode to String
            String requestBody = objectMapper.writeValueAsString(inputNode);

            InvokeEndpointRequest invokeEndpointRequest = InvokeEndpointRequest.builder()
                    .endpointName(clarifyEndpointName)
                    .contentType("application/json") // Adjust content type based on your Clarify model
                    .body(SdkBytes.fromUtf8String(requestBody))
                    .build();

            InvokeEndpointResponse invokeEndpointResponse = sageMakerRuntimeClient.invokeEndpoint(invokeEndpointRequest);
            String responseBody = invokeEndpointResponse.body().asUtf8String();

            // Parse the response from Clarify. This is highly dependent on your Clarify model's output format.
            // Example: Assuming Clarify returns something like:
            // {
            //   "explanation_summary": "Transaction flagged due to high purchase amount and suspicious IP.",
            //   "feature_contributions": {
            //     "purchase_amount": 0.6,
            //     "ip_address": 0.3,
            //     "user_agent": 0.1
            //   }
            // }
            JsonNode clarifyResponseJson = objectMapper.readTree(responseBody);

            ExplanationResult.ExplanationResultBuilder resultBuilder = ExplanationResult.builder()
                    .eventId(explainRequest.getEventId());

            if (clarifyResponseJson.has("explanation_summary")) {
                resultBuilder.explanation(clarifyResponseJson.get("explanation_summary").asText());
            }

            if (clarifyResponseJson.has("feature_contributions")) {
                Map<String, Float> featureContributions = new HashMap<>();
                JsonNode contributionsNode = clarifyResponseJson.get("feature_contributions");
                if (contributionsNode.isObject()) {
                    contributionsNode.fields().forEachRemaining(entry ->
                            featureContributions.put(entry.getKey(), (float) entry.getValue().asDouble())
                    );
                }
                resultBuilder.featureContributions(featureContributions);
            }

            logger.info("Successfully generated explanation for eventId: {}", explainRequest.getEventId());
            return resultBuilder.build();

        } catch (Exception e) {
            logger.error("Error getting explanation for eventId {}: {}", explainRequest.getEventId(), e.getMessage(), e);
            throw new RuntimeException("Failed to get explanation: " + e.getMessage(), e);
        }
    }
}
