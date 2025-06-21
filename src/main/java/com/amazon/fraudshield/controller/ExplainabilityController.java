package com.amazon.fraudshield.controller;

import com.amazon.fraudshield.model.ExplainRequest;
import com.amazon.fraudshield.model.ExplanationResult;
import com.amazon.fraudshield.service.ExplainabilityService;
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
public class ExplainabilityController {

    private static final Logger logger = LoggerFactory.getLogger(ExplainabilityController.class);

    private final ExplainabilityService explainabilityService;

    // Constructor injection for ExplainabilityService
    public ExplainabilityController(ExplainabilityService explainabilityService) {
        this.explainabilityService = explainabilityService;
    }

    /**
     * Handles POST requests to /api/fraud/explain.
     * Receives an ExplainRequest, sends it to the ExplainabilityService for explanation,
     * and returns the ExplanationResult.
     *
     * @param request The ExplainRequest object from the request body.
     * @return A ResponseEntity containing the ExplanationResult or an error message.
     */
    @PostMapping("/explain")
    public ResponseEntity<?> getExplanation(@Valid @RequestBody ExplainRequest request) {
        logger.info("Received request for explanation for eventId: {}", request.getEventId());

        try {
            ExplanationResult explanation = explainabilityService.getExplanation(request);
            logger.info("Successfully generated explanation for eventId: {}", request.getEventId());
            return ResponseEntity.ok(explanation);
        } catch (RuntimeException e) {
            logger.error("Error during explanation for eventId {}: {}", request.getEventId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to get explanation: " + e.getMessage());
        } catch (Exception e) {
            logger.error("An unexpected error occurred during explanation for eventId {}: {}", request.getEventId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred: " + e.getMessage());
        }
    }
}
