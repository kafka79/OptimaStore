package com.inventoryapp.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventoryapp.core.dto.AdjustQuantityRequest;
import com.inventoryapp.core.dto.CreateItemRequest;
import com.inventoryapp.core.dto.UpdateItemRequest;
import com.inventoryapp.core.model.Item;
import com.inventoryapp.core.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@AutoConfigureMockMvc
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InventoryService inventoryService;

    @MockBean
    private MeterRegistry meterRegistry;

    @MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @MockBean
    private javax.sql.DataSource dataSource;

    @MockBean
    private org.springframework.security.provisioning.UserDetailsManager userDetailsManager;

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createItem_shouldReturn201() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-123", "Item 1", 10, new BigDecimal("10.00"), "Electronics", 5);
        Item item = new Item(1L, "SKU-123", "Item 1", 10, new BigDecimal("10.00"), "Electronics", Instant.now(), false, 5);

        when(inventoryService.addItem(any(), eq("admin"))).thenReturn(item);

        mockMvc.perform(post("/api/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.sku").value("SKU-123"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void updateItem_shouldReturn200() throws Exception {
        UpdateItemRequest request = new UpdateItemRequest("Updated Item", new BigDecimal("15.00"), "Home");
        Item updated = new Item(1L, "SKU-123", "Updated Item", 10, new BigDecimal("15.00"), "Home", Instant.now(), false, 5);

        when(inventoryService.updateItem(eq(1L), any())).thenReturn(Optional.of(updated));

        mockMvc.perform(put("/api/items/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Item"))
                .andExpect(jsonPath("$.unitPrice").value(15.00));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adjustQuantity_shouldReturn200() throws Exception {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        Item updated = new Item(1L, "SKU-123", "Item 1", 15, new BigDecimal("10.00"), "Electronics", Instant.now(), false, 5);

        when(inventoryService.adjustStock(eq(1L), any(), eq("admin"), eq(null))).thenReturn(Optional.of(updated));

        mockMvc.perform(patch("/api/items/1/quantity")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(15));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adjustQuantity_withInvalidPayload_shouldReturn400() throws Exception {
        // Delta cannot be null, though we might not have a @NotNull on it if it's primitive. Wait, it's an int probably.
        // Let's just send empty JSON
        mockMvc.perform(patch("/api/items/1/quantity")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
