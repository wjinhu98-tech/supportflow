package dev.supportflow.assistant.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Synthetic inputs and independently reviewed expected outcomes; no local configuration is read. */
record AgentEvaluationCases(int schemaVersion, String version, List<Scenario> scenarios) {
    static AgentEvaluationCases load() throws IOException {
        return new ObjectMapper().readValue(resource("scenarios.json"), AgentEvaluationCases.class);
    }

    static byte[] resource(String name) throws IOException {
        try (var input = AgentEvaluationCases.class.getResourceAsStream("/agent-evaluation/" + name)) {
            if (input == null) throw new IOException("Missing agent evaluation resource: " + name);
            return input.readAllBytes();
        }
    }

    static String fingerprint() throws IOException {
        try {
            // Normalize line endings so the same Git checkout has the same identity on Windows and Linux.
            String text = new String(resource("scenarios.json"), StandardCharsets.UTF_8).replace("\r\n", "\n");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record Scenario(String id, Category category, Actor actor, int shop, String message, String selectedOrder,
                    PlannerFixture planner, Expected expected) { }

    enum Category { ORDER, LOGISTICS, KNOWLEDGE, CLARIFICATION, SECURITY, HUMAN_APPROVAL, RESILIENCE }
    enum Actor { BUYER, OTHER_BUYER, NO_ORDER_PERMISSION, MERCHANT, ANONYMOUS, NO_CSRF }
    enum PlannerFixture {
        RULES, FORGED_ORDER, EARLY_LOGISTICS, EARLY_FINISH, REPEATED_ORDER,
        TIMEOUT_ONCE, TEMPORARY_ALWAYS, INVALID_RESPONSE, CIRCUIT_OPEN, INFLATED_USAGE, LOGISTICS_TIMEOUT_ONCE
    }

    record Expected(int httpStatus, String responseType, Set<String> capabilities, List<String> contains,
                    List<String> excludes, String runStatus, List<String> phases, int plannerCalls,
                    int ticketDelta, String eligibility, boolean securityProbe) { }
}
