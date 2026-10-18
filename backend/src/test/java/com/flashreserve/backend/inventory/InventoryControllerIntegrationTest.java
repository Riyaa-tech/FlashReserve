package com.flashreserve.backend.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InventoryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createInventoryShouldReturnCreatedAndPersistInventory()
            throws Exception {

        String sku = "TEST-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "%s",
                                  "name": "Integration Test Product",
                                  "totalQuantity": 10
                                }
                                """.formatted(sku)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.name")
                        .value("Integration Test Product"))
                .andExpect(jsonPath("$.totalQuantity").value(10))
                .andExpect(jsonPath("$.availableQuantity").value(10))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void createInventoryShouldRejectZeroQuantity() throws Exception {

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "TEST-ZERO",
                                  "name": "Invalid Product",
                                  "totalQuantity": 0
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAllInventoryShouldReturnOkAndAnArray() throws Exception {

        mockMvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getInventoryWithUnknownIdShouldReturnNotFound()
            throws Exception {

        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/inventory/{id}", unknownId))
                .andExpect(status().isNotFound());
    }

    @Test
    void creatingDuplicateSkuShouldReturnConflict() throws Exception {

        String sku = "TEST-DUPLICATE-" + UUID.randomUUID();

        String requestBody = """
                {
                  "sku": "%s",
                  "name": "Duplicate Test Product",
                  "totalQuantity": 5
                }
                """.formatted(sku);

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());
    }
}
