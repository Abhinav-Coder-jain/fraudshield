package com.amazon.fraudshield.controller;

import com.amazon.fraudshield.model.GenerationRequest;
import com.amazon.fraudshield.service.FraudGenerationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fraud") // Base path for fraud-related endpoints
public class FraudGenerationController {

    private static final Logger logger = LoggerFactory.getLogger(FraudGenerationController.class);

    private final FraudGenerationService fraudGenerationService;

    // Constructor for dependency injection
    public FraudGenerationController(FraudGenerationService fraudGenerationService) {
        this.fraudGenerationService = fraudGenerationService;
    }

    /**
     * Handles POST requests to /api/fraud/generate to trigger synthetic fraud data generation.
     *
     * @param request The GenerationRequest containing details for fraud generation.
     * @return ResponseEntity with a success message or an error message.
     */
    @PostMapping("/generate")
    public ResponseEntity<String> generateFraud(@Valid @RequestBody GenerationRequest request) {
        logger.info("Received request to generate synthetic fraud: {}", request);
        try {
            String result = fraudGenerationService.generateFraud(request);
            if (result.startsWith("Failed")) {
                return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error processing fraud generation request: {}", e.getMessage(), e);
            return new ResponseEntity<>("Internal server error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
