package com.amazon.fraudshield.service;

import com.amazon.fraudshield.model.GeneratedFraudData;
import com.amazon.fraudshield.model.TrainingRequest;
import com.amazon.fraudshield.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference; // Explicitly imported
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody; // Explicitly imported
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.AlgorithmSpecification;
import software.amazon.awssdk.services.sagemaker.model.Channel;
import software.amazon.awssdk.services.sagemaker.model.CreateTrainingJobRequest;
import software.amazon.awssdk.services.sagemaker.model.CreateTrainingJobResponse;
import software.amazon.awssdk.services.sagemaker.model.S3DataSource;
import software.amazon.awssdk.services.sagemaker.model.S3DataType;
import software.amazon.awssdk.services.sagemaker.model.DescribeTrainingJobRequest;
import software.amazon.awssdk.services.sagemaker.model.DescribeTrainingJobResponse;
import software.amazon.awssdk.services.sagemaker.model.OutputDataConfig;
import software.amazon.awssdk.services.sagemaker.model.ResourceConfig;
import software.amazon.awssdk.services.sagemaker.model.StoppingCondition;
import software.amazon.awssdk.services.sagemaker.model.TrainingJobStatus;
import software.amazon.awssdk.services.sagemaker.model.TrainingInputMode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest; // Explicitly imported
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request; // Explicitly imported
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response; // Explicitly imported
import software.amazon.awssdk.services.s3.model.S3Object; // Explicitly imported
import software.amazon.awssdk.services.s3.model.PutObjectRequest; // Explicitly imported

// Fraud Detector imports
import software.amazon.awssdk.services.frauddetector.FraudDetectorClient;
import software.amazon.awssdk.services.frauddetector.model.CreateModelVersionRequest;
import software.amazon.awssdk.services.frauddetector.model.CreateModelVersionResponse;
import software.amazon.awssdk.services.frauddetector.model.ModelTypeEnum;
import software.amazon.awssdk.services.frauddetector.model.TrainingDataSourceEnum;
import software.amazon.awssdk.services.frauddetector.model.ExternalEventsDetail;
// IMPORTS FOR TRAINING DATA SCHEMA
import software.amazon.awssdk.services.frauddetector.model.TrainingDataSchema;
import software.amazon.awssdk.services.frauddetector.model.LabelSchema;
import software.amazon.awssdk.services.frauddetector.model.UnlabeledEventsTreatment;


import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ModelTrainingService {

    private static final Logger logger = LoggerFactory.getLogger(ModelTrainingService.class);

    private final SageMakerClient sageMakerClient;
    private final FraudDetectorClient fraudDetectorClient;
    private final S3Client s3Client;

    @Value("${app.s3.data-bucket-name}")
    private String s3DataBucketName;

    @Value("${app.sagemaker.execution-role-arn}")
    private String sagemakerExecutionRoleArn;

    @Value("${app.fraud-detector.detector-id}")
    private String fraudDetectorId;

    /**
     * Role that Fraud Detector uses to read your external events data from S3.
     * You must set this in application.properties:
     * app.fraud-detector.external-events-role-arn=arn:aws:iam::123456789012:role/YourFraudDetectorDataAccessRole
     */
    @Value("${app.fraud-detector.external-events-role-arn}")
    private String externalEventsRoleArn;

    public ModelTrainingService(SageMakerClient sageMakerClient, FraudDetectorClient fraudDetectorClient, S3Client s3Client) {
        this.sageMakerClient = sageMakerClient;
        this.fraudDetectorClient = fraudDetectorClient;
        this.s3Client = s3Client;
    }

    /**
     * Prepares the combined training data by reading real and synthetic data from S3,
     * combining them, and uploading the combined dataset to a new S3 location.
     *
     * @param realDataS3Path       S3 URI prefix for real data (e.g., s3://bucket/training-data/real/)
     * @param syntheticDataS3Path  S3 URI prefix for synthetic data (e.g., s3://bucket/synthetic-fraud/)
     * @return S3 URI of the combined dataset.
     * @throws Exception if data reading or combining fails.
     */
    private String prepareCombinedTrainingData(String realDataS3Path, String syntheticDataS3Path) throws Exception {
        logger.info("Preparing combined training data from real: {} and synthetic: {}", realDataS3Path, syntheticDataS3Path);

        // 1. Read Real Data (CSV)
        List<String[]> realRecords = new ArrayList<>();
        Map<String, Integer> realDataHeaderMap = new HashMap<>(); // To store column name -> index mapping

        ListObjectsV2Request listRealRequest = ListObjectsV2Request.builder()
                .bucket(s3DataBucketName)
                .prefix(realDataS3Path.replace("s3://" + s3DataBucketName + "/", "")) // Extract prefix from URI
                .build();
        ListObjectsV2Response listRealResponse = s3Client.listObjectsV2(listRealRequest);

        String realDataKey = null;
        for (S3Object obj : listRealResponse.contents()) {
            if (obj.key().endsWith(".csv")) {
                realDataKey = obj.key();
                break;
            }
        }

        if (realDataKey == null) {
            throw new RuntimeException("No CSV file found in realDataS3Path: " + realDataS3Path);
        }

        GetObjectRequest getRealRequest = GetObjectRequest.builder()
                .bucket(s3DataBucketName)
                .key(realDataKey)
                .build();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(s3Client.getObject(getRealRequest), StandardCharsets.UTF_8))) {
            String line;
            // Read header to create column index map
            if ((line = reader.readLine()) != null) {
                String[] headers = line.split(",");
                for (int i = 0; i < headers.length; i++) {
                    realDataHeaderMap.put(headers[i].trim(), i);
                }
            } else {
                throw new RuntimeException("Real data CSV file is empty or has no header.");
            }

            // Read data rows
            while ((line = reader.readLine()) != null) {
                realRecords.add(line.split(",")); // Simple CSV split
            }
        }
        logger.info("Read {} records from real data.", realRecords.size());

        // 2. Read Synthetic Data (JSON)
        List<GeneratedFraudData> syntheticRecords = new ArrayList<>();
        ListObjectsV2Request listSyntheticRequest = ListObjectsV2Request.builder()
                .bucket(s3DataBucketName)
                .prefix(syntheticDataS3Path.replace("s3://" + s3DataBucketName + "/", ""))
                .build();
        ListObjectsV2Response listSyntheticResponse = s3Client.listObjectsV2(listSyntheticRequest);

        for (S3Object obj : listSyntheticResponse.contents()) {
            if (obj.key().endsWith(".json")) {
                GetObjectRequest getSyntheticRequest = GetObjectRequest.builder()
                        .bucket(s3DataBucketName)
                        .key(obj.key())
                        .build();
                String jsonContent;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(s3Client.getObject(getSyntheticRequest), StandardCharsets.UTF_8))) {
                    jsonContent = reader.lines().collect(Collectors.joining("\n"));
                }
                List<GeneratedFraudData> fraudData = JsonUtil.OBJECT_MAPPER.readValue(jsonContent, new TypeReference<List<GeneratedFraudData>>() {});
                syntheticRecords.addAll(fraudData);
            }
        }
        logger.info("Read {} records from synthetic data.", syntheticRecords.size());

        // 3. Combine Data and Convert to a Unified CSV Format for Fraud Detector
        List<String> combinedCsvLines = new ArrayList<>();
        // Header for the COMBINED CSV: EVENT_LABEL and EVENT_TIMESTAMP are required special fields
        // The remaining variables should match the Event Type's variable names (lowercase, snake_case)
        combinedCsvLines.add("EVENT_LABEL,EVENT_TIMESTAMP,purchase_amount,ip_address,device_id,payment_method");

        // Add real data - using header map for robustness
        for (String[] record : realRecords) {
            String eventLabel = getCellValue(record, realDataHeaderMap, "EVENT_LABEL", "0"); // Default 0 for real data
            String eventTimestamp = getCellValue(record, realDataHeaderMap, "eventTimestamp", Instant.now().toString()); // Use the column name from real_data.csv (lowercase)
            String purchaseAmount = getCellValue(record, realDataHeaderMap, "purchaseAmount", "0.0");
            String ipAddress = getCellValue(record, realDataHeaderMap, "ipAddress", "0.0.0.0");
            String deviceId = getCellValue(record, realDataHeaderMap, "device_id", "unknown_device");
            String paymentMethod = getCellValue(record, realDataHeaderMap, "payment_method", "unknown_method");

            // Order must match header: EVENT_LABEL,EVENT_TIMESTAMP,purchase_amount,ip_address,device_id,payment_method
            combinedCsvLines.add(eventLabel + "," + eventTimestamp + "," + purchaseAmount + "," + ipAddress + "," + deviceId + "," + paymentMethod);
        }

        // Add synthetic data
        for (GeneratedFraudData record : syntheticRecords) {
            String label = record.getFraudLabel().equals("FRAUD") ? "1" : "0"; // Map FRAUD to 1, others to 0
            // Order must match header: EVENT_LABEL,EVENT_TIMESTAMP,purchase_amount,ip_address,device_id,payment_method
            combinedCsvLines.add(
                    label + "," +
                            record.getTransactionTimestamp() + "," + // Synthetic transactionTimestamp (already Instant and ISO 8601 with Z) -> EVENT_TIMESTAMP
                            record.getAmount() + "," +               // Synthetic amount -> purchase_amount
                            record.getIpAddress() + "," +            // Synthetic ipAddress -> ip_address
                            record.getDeviceId() + "," +             // Synthetic deviceId -> device_id
                            record.getPaymentMethod()                // Synthetic paymentMethod -> payment_method
            );
        }
        logger.info("Combined {} lines for training.", combinedCsvLines.size());

        // 4. Upload combined data to S3
        String combinedKey = "training-data/combined/combined_fraud_data_" + Instant.now().toEpochMilli() + ".csv";
        String combinedS3Uri = "s3://" + s3DataBucketName + "/" + combinedKey;

        PutObjectRequest putCombinedRequest = PutObjectRequest.builder()
                .bucket(s3DataBucketName)
                .key(combinedKey)
                .contentType("text/csv")
                .build();

        String combinedDataContent = String.join("\n", combinedCsvLines);
        s3Client.putObject(putCombinedRequest, RequestBody.fromString(combinedDataContent));
        logger.info("Combined training data uploaded to S3: {}", combinedS3Uri);

        return combinedS3Uri;
    }

    /**
     * Helper method to safely retrieve cell values using header map.
     */
    private String getCellValue(String[] record, Map<String, Integer> headerMap, String columnName, String defaultValue) {
        Integer index = headerMap.get(columnName);
        if (index != null && index >= 0 && index < record.length) {
            String value = record[index];
            return (value != null && !value.trim().isEmpty()) ? value.trim() : defaultValue;
        }
        logger.warn("Column '{}' not found or out of bounds in real data record. Using default value: {}", columnName, defaultValue);
        return defaultValue;
    }


    /**
     * 1. Start a SageMaker training job with given real & synthetic data.
     * 2. Poll until completion.
     * 3. Register a Fraud Detector model version that trains on external events from S3.
     *
     * Note: This does NOT “import a SageMaker model artifact” into Fraud Detector. Instead,
     * Fraud Detector will itself train a model version on the external events data
     * located at s3://{s3DataBucketName}/training-data/ (or as specified).
     *
     * If your goal is to have Fraud Detector call a SageMaker endpoint at inference,
     * use PutExternalModel APIs (not shown here).
     */
    public String trainModel(TrainingRequest request) {
        logger.info("Starting SageMaker model training for model: {}", request.getModelName());

        // Unique name for SageMaker training job
        String trainingJobName = request.getModelName() + "-" + System.currentTimeMillis();
        String outputModelS3Uri = "s3://" + s3DataBucketName + "/model-artifacts/" + trainingJobName + "/";

        try {
            // Call the new method to prepare combined data
            String combinedDataS3Uri = prepareCombinedTrainingData(request.getRealDataS3Path(), request.getSyntheticDataS3Path());

            // 1. Configure SageMaker Training Job
            String trainingImage = "683313688378.dkr.ecr.us-east-1.amazonaws.com/sagemaker-xgboost:1.7-1";

            AlgorithmSpecification algorithmSpecification = AlgorithmSpecification.builder()
                    .trainingImage(trainingImage)
                    .trainingInputMode(TrainingInputMode.FILE)
                    .build();

            // SageMaker input channel: now only one 'train' channel with combined data
            Channel trainDataChannel = Channel.builder()
                    .channelName("train")
                    .dataSource(
                            software.amazon.awssdk.services.sagemaker.model.DataSource.builder() // Fully qualified name
                                    .s3DataSource(
                                            S3DataSource.builder()
                                                    .s3Uri(combinedDataS3Uri)
                                                    .s3DataType(S3DataType.S3_PREFIX)
                                                    .build()
                                    )
                                    .build()
                    )
                    .contentType("text/csv")
                    .build();

            List<Channel> inputChannels = new ArrayList<>();
            inputChannels.add(trainDataChannel);

            OutputDataConfig outputDataConfig = OutputDataConfig.builder()
                    .s3OutputPath(outputModelS3Uri)
                    .build();

            ResourceConfig resourceConfig = ResourceConfig.builder()
                    .instanceCount(request.getInstanceType() != null ? 1 : 1)
                    .instanceType(request.getInstanceType() != null
                            ? request.getInstanceType()
                            : "ml.m5.xlarge")
                    .volumeSizeInGB(20)
                    .build();

            CreateTrainingJobRequest createTrainingJobRequest = CreateTrainingJobRequest.builder()
                    .trainingJobName(trainingJobName)
                    .algorithmSpecification(algorithmSpecification)
                    .roleArn(sagemakerExecutionRoleArn)
                    .inputDataConfig(inputChannels)
                    .outputDataConfig(outputDataConfig)
                    .resourceConfig(resourceConfig)
                    .stoppingCondition(StoppingCondition.builder().maxRuntimeInSeconds(3600).build())
                    .hyperParameters(
                            java.util.Collections.singletonMap("num_round", "100")
                    )
                    .build();

            CreateTrainingJobResponse trainingJobResponse = sageMakerClient.createTrainingJob(createTrainingJobRequest);
            logger.info("SageMaker training job created: {}", trainingJobResponse.trainingJobArn());

            // 2. Poll for completion
            TrainingJobStatus jobStatus = TrainingJobStatus.IN_PROGRESS;
            String modelArtifactS3Location = null;
            long startTime = System.currentTimeMillis();
            long timeoutMillis = TimeUnit.MINUTES.toMillis(60);

            do {
                DescribeTrainingJobResponse describeResponse = sageMakerClient.describeTrainingJob(
                        DescribeTrainingJobRequest.builder()
                                .trainingJobName(trainingJobName)
                                .build()
                );
                jobStatus = describeResponse.trainingJobStatus();
                logger.info("SageMaker Training Job Status for {}: {}", trainingJobName, jobStatus);

                if (describeResponse.modelArtifacts() != null && describeResponse.modelArtifacts().s3ModelArtifacts() != null) {
                    modelArtifactS3Location = describeResponse.modelArtifacts().s3ModelArtifacts();
                }

                if (jobStatus == TrainingJobStatus.COMPLETED
                        || jobStatus == TrainingJobStatus.FAILED
                        || jobStatus == TrainingJobStatus.STOPPED) {
                    break;
                }
                TimeUnit.SECONDS.sleep(30);
            } while (System.currentTimeMillis() - startTime < timeoutMillis);

            if (jobStatus != TrainingJobStatus.COMPLETED) {
                String err = "SageMaker training did not complete successfully. Final Status: " + jobStatus;
                logger.error(err);
                return "Failed to train model: " + err;
            }

            if (modelArtifactS3Location == null || modelArtifactS3Location.isEmpty()) {
                String err = "Model artifact location not found after training succeeded. This is unexpected.";
                logger.error(err);
                return "Failed to train model: " + err;
            }
            logger.info("SageMaker training completed. Model artifact at {}", modelArtifactS3Location);

            // 3. Register Model Version with Fraud Detector using external events
            ExternalEventsDetail externalEventsDetail = ExternalEventsDetail.builder()
                    .dataLocation("s3://" + s3DataBucketName + "/training-data/combined/") // FIX: Point directly to the 'combined' folder
                    .dataAccessRoleArn(externalEventsRoleArn)
                    .build();

            // Define Model Input Variables based on your combined CSV header and Event Type variables
            // These variable names MUST match the variables defined in your Amazon Fraud Detector Event Type.
            List<String> modelInputVariables = new ArrayList<>();
            // Include all 5 variables from your Event Type:
            modelInputVariables.add("ip_address");
            modelInputVariables.add("purchase_amount");
            modelInputVariables.add("payment_method");
            modelInputVariables.add("event_timestamp");
            modelInputVariables.add("device_id");

            // Define Label Schema
            Map<String, List<String>> labelMapper = new HashMap<>();
            labelMapper.put("FRAUD", Collections.singletonList("1")); // Map "FRAUD" to label "1" in CSV
            labelMapper.put("LEGIT", Collections.singletonList("0")); // Map "LEGIT" to label "0" in CSV

            LabelSchema labelSchema = LabelSchema.builder()
                    .unlabeledEventsTreatment(UnlabeledEventsTreatment.IGNORE)
                    .labelMapper(labelMapper)
                    .build();

            // Build TrainingDataSchema
            TrainingDataSchema trainingDataSchema = TrainingDataSchema.builder()
                    .modelVariables(modelInputVariables)
                    .labelSchema(labelSchema)
                    .build();


            CreateModelVersionRequest createModelVersionRequest = CreateModelVersionRequest.builder()
                    .modelId(fraudDetectorId)
                    .modelType(ModelTypeEnum.ONLINE_FRAUD_INSIGHTS)
                    .trainingDataSource(TrainingDataSourceEnum.EXTERNAL_EVENTS)
                    .externalEventsDetail(externalEventsDetail)
                    .trainingDataSchema(trainingDataSchema)
                    .build();

            CreateModelVersionResponse modelVersionResponse = fraudDetectorClient.createModelVersion(createModelVersionRequest);
            logger.info("Fraud Detector model version created: modelId={}, version={}",
                    modelVersionResponse.modelId(), modelVersionResponse.modelVersionNumber());

            return "SageMaker job completed and Fraud Detector version created. SageMaker ARN: "
                    + trainingJobResponse.trainingJobArn()
                    + ", Fraud Detector version: "
                    + modelVersionResponse.modelVersionNumber();

        } catch (Exception e) {
            logger.error("Error during SageMaker training or Fraud Detector registration: {}", e.getMessage(), e);
            return "Failed to train/register model: " + e.getMessage();
        }
    }
}
