package com.amazon.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant; // Import java.time.Instant

@Data // Lombok annotation
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratedFraudData {
    private String transactionId;
    private String userId;
    private double amount;
    private String currency;
    private String ipAddress;
    private String deviceId;
    private String paymentMethod;
    private Instant transactionTimestamp; // CHANGED: Now java.time.Instant
    private String fraudLabel; // "FRAUD" for synthetic data

    // Add more fields as per the complexity of synthetic fraud data you expect
    private String reason; // Why this specific record is fraudulent (e.g., "synthetic identity")
}
