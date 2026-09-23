package com.avr.examples;

import com.avr.api.Tool;
import com.avr.core.tool.WorkspaceTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardToolSchemaTest {
    @Test
    void standardToolParametersAreValidJsonObjects() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Tool tool : WorkspaceTools.defaults()) {
            JsonNode parameters = mapper.readTree(tool.definition().getInputSchema());
            assertEquals("object", parameters.path("type").asText(),
                    tool.definition().getName());
            assertTrue(parameters.path("properties").isObject(),
                    tool.definition().getName());
        }
    }
}
