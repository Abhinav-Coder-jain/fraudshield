package com.amazon.fraudshield.service;

import com.amazon.fraudshield.model.GeneratedFraudData;
import com.amazon.fraudshield.model.GenerationRequest;
import com.amazon.fraudshield.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FraudGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(FraudGenerationService.class);
    // DATE_TIME_FORMATTER is used for String conversion when needed (e.g., for logging or S3 content)
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final BedrockRuntimeClient bedrockClient;
    private final S3Client s3Client;

    @Value("${app.s3.data-bucket-name}")
    private String s3DataBucketName;

    @Value("${app.bedrock.model-id}")
    private String bedrockModelId; // This should be set in application.properties (e.g., cohere.command-r-plus-v1:0)

    public FraudGenerationService(BedrockRuntimeClient bedrockClient, S3Client s3Client) {
        this.bedrockClient = bedrockClient;
        this.s3Client = s3Client;
    }

    public String generateFraud(GenerationRequest request) {
        logger.info("Starting synthetic fraud generation for type: {} with count: {}", request.getFraudType(), request.getCount());

        List<GeneratedFraudData> allGeneratedData = new ArrayList<>();
        int generatedCount = 0;
        int batchSize = Math.min(request.getCount(), 10); // Generate in batches, max 10 per call

        while (generatedCount < request.getCount()) {
            int currentBatchCount = Math.min(batchSize, request.getCount() - generatedCount);
            if (currentBatchCount == 0) break;

            String prompt = createPrompt(request.getFraudType(), currentBatchCount, request.getAdditionalDetails());

            InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
                    .modelId(bedrockModelId)
                    .body(SdkBytes.fromUtf8String(prompt))
                    .contentType("application/json")
                    .accept("application/json")
                    .build();

            try {
                InvokeModelResponse invokeResponse = bedrockClient.invokeModel(invokeRequest);
                String rawResponse = invokeResponse.body().asUtf8String();
                logger.info("Successfully invoked Bedrock. Raw response: {}", rawResponse);

                // Extract content from the "content" field of the Bedrock response
                JsonNode rootNode = JsonUtil.OBJECT_MAPPER.readTree(rawResponse);
                String contentString = rootNode.at("/choices/0/message/content").asText();

                // Extract JSON array from markdown code block if present
                Pattern pattern = Pattern.compile("```json\\s*\\[([\\s\\S]*?)\\]\\s*```");
                Matcher matcher = pattern.matcher(contentString);
                String jsonArrayString;
                if (matcher.find()) {
                    // This captures content between [ and ] within the ```json block
                    jsonArrayString = "[" + matcher.group(1) + "]";
                } else {
                    // Fallback if no markdown block, assume contentString is directly the JSON array
                    jsonArrayString = contentString;
                }

                // Attempt to parse the extracted JSON string
                // The TypeReference assumes that the JSON can be directly mapped to List<GeneratedFraudData>
                // This means GeneratedFraudData's transactionTimestamp field's Jackson annotation or type should handle parsing "2024-..." string to Instant
                List<GeneratedFraudData> batchData = JsonUtil.OBJECT_MAPPER.readValue(jsonArrayString, new TypeReference<List<GeneratedFraudData>>() {});

                allGeneratedData.addAll(batchData);
                generatedCount += batchData.size();
                logger.info("Generated {} records in current batch. Total generated: {}", batchData.size(), generatedCount);

            } catch (Exception e) {
                logger.error("Error during synthetic fraud generation Bedrock invocation: {}", e.getMessage(), e);
                // Continue to next batch even if one fails, or decide to throw based on severity
            }

            // Small delay to avoid hitting Bedrock rate limits
            try {
                Thread.sleep(1000); // 1 second delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted during delay between Bedrock calls.", e);
            }
        }

        if (allGeneratedData.isEmpty()) {
            return "No synthetic fraud data could be generated.";
        }

        // Apply any post-processing (like ensuring specific timestamps/IDs)
        List<GeneratedFraudData> processedData = postProcessGeneratedData(allGeneratedData);

        // Upload to S3
        String key = "synthetic-fraud/" + request.getFraudType() + "_" + Instant.now().toEpochMilli() + ".json";
        String s3Uri = "s3://" + s3DataBucketName + "/" + key;

        try {
            // When writing to S3, convert Instant to String using DATE_TIME_FORMATTER
            // If GeneratedFraudData.transactionTimestamp is Instant, Jackson will serialize it to ISO 8601 by default
            // If you need specific formatting for the stored JSON, apply it here before writing.
            String jsonContent = JsonUtil.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(processedData);
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3DataBucketName)
                    .key(key)
                    .contentType("application/json")
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromString(jsonContent));
            logger.info("Generated synthetic fraud data uploaded to S3: {}", s3Uri);
            return "Successfully generated and stored " + processedData.size() + " synthetic fraud records to " + s3Uri;
        } catch (IOException e) {
            logger.error("Error storing generated fraud data to S3: {}", e.getMessage(), e);
            return "Failed to store generated fraud data: " + e.getMessage();
        }
    }

    private String createPrompt(String fraudType, int count, String additionalDetails) {
        // Refined prompt to encourage complete JSON and specific formatting
        String prompt = String.format("""
        Please act as a data generator for an online payment fraud detection system.
        I need you to generate a JSON array of %d synthetic transaction records.
        Each record should be an object with the following fields:
        - "transactionId": A unique UUID for the transaction.
        - "userId": A unique user identifier (e.g., "user_JohnDoe" or a UUID).
        - "amount": A floating-point number representing the transaction amount (e.g., 123.45).
        - "currency": The currency (e.g., "USD").
        - "ipAddress": A valid IPv4 address (e.g., "192.168.1.1").
        - "deviceId": A unique device identifier (e.g., "device_xyz" or a UUID).
        - "paymentMethod": The payment method used (e.g., "Credit Card", "Debit Card", "PayPal").
        - "transactionTimestamp": An ISO 8601 formatted timestamp with 'Z' for UTC (e.g., "2024-01-01T12:30:00Z").
        - "fraudLabel": Must be either "FRAUD" or "LEGIT". Ensure a balanced mix, especially for smaller counts.
        - "reason": A brief, plausible reason for the fraudLabel (e.g., "Suspicious activity detected", "Legitimate transaction pattern", "Multiple attempted transactions", "Unusual spending location").

        Ensure the output is strictly a JSON array of objects, wrapped in a markdown code block (```json...```).
        Do not include any other text or explanation outside the JSON block.
        
        Fraud Type: %s
        Additional Details/Constraints: %s
        """, count, fraudType, additionalDetails != null ? additionalDetails : "");

        // Anthropic Claude model prompt structure (Jamba-instruct can also use this for better adherence)
        // If your bedrock model is not Claude, you might need to adjust this prompt structure.

        ArrayNode stopSequencesArray = JsonUtil.OBJECT_MAPPER.createArrayNode();
        for (String seq : List.of("```")) {
            stopSequencesArray.add(seq);
        }

        return JsonUtil.OBJECT_MAPPER.createObjectNode()
                .put("prompt", "Human: " + prompt + "\n\nAssistant: ```json\n")
                .put("max_tokens_to_sample", 4000)
                .put("temperature", 0.7)
                .put("top_p", 0.9)
                .set("stop_sequences", stopSequencesArray) // Use set for ArrayNode
                .toString();
    }


    /**
     * Post-processes the generated data to ensure UUIDs are present and timestamps are in the correct format.
     * Assumes GeneratedFraudData.transactionTimestamp is of type Instant.
     * Jackson's ObjectMapper typically handles conversion from ISO 8601 String to Instant automatically
     * if the field in the model is Instant.
     */
    private List<GeneratedFraudData> postProcessGeneratedData(List<GeneratedFraudData> data) {
        List<GeneratedFraudData> processedList = new ArrayList<>();
        for (GeneratedFraudData record : data) {
            // Ensure transactionId and userId are UUIDs if they look like placeholder
            if (record.getTransactionId() == null || record.getTransactionId().isEmpty() || record.getTransactionId().startsWith("transaction_")) {
                record.setTransactionId(UUID.randomUUID().toString());
            }
            if (record.getUserId() == null || record.getUserId().isEmpty() || record.getUserId().startsWith("user_")) {
                record.setUserId("user_" + UUID.randomUUID().toString().substring(0, 8)); // Shorten for readability
            }
            if (record.getDeviceId() == null || record.getDeviceId().isEmpty() || record.getDeviceId().startsWith("device_")) {
                record.setDeviceId("device_" + UUID.randomUUID().toString().substring(0, 8)); // Shorten for readability
            }

            // Ensure timestamp is not null and is a valid Instant
            if (record.getTransactionTimestamp() == null) {
                record.setTransactionTimestamp(Instant.now()); // Set a default if null
            }

            // FIX for: Operator '==' cannot be applied to 'double', 'null'
            // Since record.getAmount() is a primitive double, it can never be null.
            // Only check if it's less than or equal to 0.0.
            if ("FRAUD".equalsIgnoreCase(record.getFraudLabel()) && (record.getAmount() <= 0.0)) {
                record.setAmount(10.0 + (Math.random() * 4990.0)); // Default to a reasonable fraud amount
            }

            // Clean up reason field if it's too long or contains unwanted characters
            if (record.getReason() != null && record.getReason().length() > 255) {
                record.setReason(record.getReason().substring(0, 250) + "...");
            }

            processedList.add(record);
        }
        return processedList;
    }
}
