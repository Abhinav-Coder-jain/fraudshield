package com.amazon.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import java.time.Instant;

/**
 * Data Transfer Object (DTO) representing an incoming transaction event
 * for fraud detection, specifically aligned with the 'online_payment_transaction'
 * Event Type variables configured in Amazon Fraud Detector.
 */
@Data // Lombok annotation to generate getters, setters, toString, equals, hashCode
@NoArgsConstructor // Lombok annotation to generate no-argument constructor
@AllArgsConstructor // Lombok annotation to generate constructor with all fields
@Builder // Lombok annotation to generate a builder pattern for object creation
public class TransactionEvent {

    @NotBlank(message = "Event ID cannot be blank")
    private String eventId;

    /**
     * Fraud Detector event type name. For this setup, it should match 'online_payment_transaction'.
     */
    @NotBlank(message = "Event Type cannot be blank")
    private String eventType;

    @NotBlank(message = "Entity Type cannot be blank")
    private String entityType; // e.g., "customer"

    @NotBlank(message = "Entity ID cannot be blank")
    private String entityId;   // e.g., "customer123"

    @NotBlank(message = "IP Address cannot be blank")
    private String ipAddress; // Maps to 'ip_address' in Fraud Detector

    @NotNull(message = "Purchase Amount cannot be null")
    @DecimalMin(value = "0.0", inclusive = false, message = "Purchase Amount must be positive")
    private Double purchaseAmount; // Maps to 'purchase_amount' in Fraud Detector

    @NotBlank(message = "Device ID cannot be blank")
    private String deviceId; // Maps to 'device_id' in Fraud Detector

    @NotBlank(message = "Payment Method cannot be blank")
    private String paymentMethod; // Maps to 'payment_method' in Fraud Detector

    /**
     * Timestamp of the event, required by Fraud Detector.
     * Represented as java.time.Instant; Jackson will serialize to ISO-8601 format (e.g., 2023-01-01T12:00:00Z).
     * This maps to 'event_timestamp' in Fraud Detector.
     */
    @NotNull(message = "Event timestamp cannot be null")
    private Instant eventTimestamp;

    // Removed emailAddress, userAgent, behavioralData as they are not defined as direct
    // variables in our current Fraud Detector event type configuration for the model.
    // If you add them back to the Fraud Detector Event Type later, re-add them here
    // and update FraudDetectionService accordingly.
}
