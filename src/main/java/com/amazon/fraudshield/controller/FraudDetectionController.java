package com.amazon.fraudshield.controller;

import com.amazon.fraudshield.model.PredictionResult;
import com.amazon.fraudshield.model.TransactionEvent;
import com.amazon.fraudshield.service.FraudDetectionService;
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
@RequestMapping("/api/fraud") // Base path for all endpoints in this controller
public class FraudDetectionController {

    private static final Logger logger = LoggerFactory.getLogger(FraudDetectionController.class);

    private final FraudDetectionService fraudDetectionService;

    // Constructor injection for FraudDetectionService
    public FraudDetectionController(FraudDetectionService fraudDetectionService) {
        this.fraudDetectionService = fraudDetectionService;
    }

    /**
     * Handles POST requests to /api/fraud/predict.
     * Receives a TransactionEvent, sends it to the FraudDetectionService for prediction,
     * and returns the PredictionResult.
     *
     * @param event The TransactionEvent object from the request body.
     * @return A ResponseEntity containing the PredictionResult or an error message.
     */
    @PostMapping("/predict")
    public ResponseEntity<?> predictFraud(@Valid @RequestBody TransactionEvent event) {
        logger.info("Received request for fraud prediction for eventId: {}", event.getEventId());

        try {
            PredictionResult prediction = fraudDetectionService.getFraudPrediction(event);
            logger.info("Successfully generated fraud prediction for eventId: {}", event.getEventId());
            return ResponseEntity.ok(prediction);
        } catch (RuntimeException e) {
            logger.error("Error during fraud prediction for eventId {}: {}", event.getEventId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to get fraud prediction: " + e.getMessage());
        } catch (Exception e) {
            logger.error("An unexpected error occurred during fraud prediction for eventId {}: {}", event.getEventId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred: " + e.getMessage());
        }
    }
}
