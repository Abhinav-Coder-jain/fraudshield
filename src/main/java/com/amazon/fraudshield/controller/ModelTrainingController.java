package com.amazon.fraudshield.controller;

import com.amazon.fraudshield.model.TrainingRequest;
import com.amazon.fraudshield.service.ModelTrainingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid; // For input validation

@RestController // Marks this class as a Spring REST Controller
@RequestMapping("/api/model") // Base path for all endpoints in this controller
public class ModelTrainingController {

    private static final Logger logger = LoggerFactory.getLogger(ModelTrainingController.class);

    private final ModelTrainingService modelTrainingService;

    // Constructor injection for ModelTrainingService
    public ModelTrainingController(ModelTrainingService modelTrainingService) {
        this.modelTrainingService = modelTrainingService;
    }

    /**
     * Handles POST requests to /api/model/train.
     * Receives a TrainingRequest, sends it to the ModelTrainingService to initiate training,
     * and returns the training job status/ARN.
     *
     * @param request The TrainingRequest object from the request body.
     * @return A ResponseEntity containing the training status message or an error message.
     */
    @PostMapping("/train")
    public ResponseEntity<?> trainModel(@Valid @RequestBody TrainingRequest request) {
        logger.info("Received request to train model: {}", request.getModelName());

        try {
            String trainingStatus = modelTrainingService.trainModel(request);
            logger.info("Model training process initiated for {}: {}", request.getModelName(), trainingStatus);
            return ResponseEntity.ok(trainingStatus);
        } catch (RuntimeException e) {
            logger.error("Error during model training for {}: {}", request.getModelName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to initiate model training: " + e.getMessage());
        } catch (Exception e) {
            logger.error("An unexpected error occurred during model training for {}: {}", request.getModelName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred: " + e.getMessage());
        }
    }
}
