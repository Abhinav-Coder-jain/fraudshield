package com.amazon.fraudshield.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for JSON serialization/deserialization using Jackson.
 * Configured to handle java.time types (e.g., Instant) via JavaTimeModule.
 */
public class JsonUtil {

    private static final Logger logger = LoggerFactory.getLogger(JsonUtil.class);

    public static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Register Java Time module so Instant and other java.time types serialize as ISO-8601
        mapper.registerModule(new JavaTimeModule());
        // Disable writing dates as numeric timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Converts a Java object to a JSON string.
     *
     * @param object The Java object to convert.
     * @return A JSON string representation of the object, or null if an error occurs.
     */
    public static String toJsonString(Object object) {
        try {
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            logger.error("Error converting object to JSON string: {}", object, e);
            return null;
        }
    }

    /**
     * Converts a JSON string to a Java object of the specified class.
     *
     * @param jsonString The JSON string to convert.
     * @param clazz      The Class object representing the type to convert the JSON to.
     * @param <T>        The type of the object.
     * @return A Java object of the specified type, or null if an error occurs.
     */
    public static <T> T fromJsonString(String jsonString, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(jsonString, clazz);
        } catch (JsonProcessingException e) {
            logger.error("Error converting JSON string to object of type {}: {}", clazz.getName(), jsonString, e);
            return null;
        }
    }
}
