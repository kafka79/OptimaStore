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
import java.util.List;
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

    private final Item sampleItem = new Item(1L, "SKU-123", "Item 1", 10, new BigDecimal("10.00"), "Electronics", Instant.now(), false, 5, 0);

    @org.junit.jupiter.api.BeforeEach
    void setUpRedisMock() {
        when(stringRedisTemplate.execute(
            any(org.springframework.data.redis.core.script.RedisScript.class), 
            any(java.util.List.class), 
            any(Object[].class)
        )).thenAnswer(invocation -> 1L);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createItem_shouldReturn201() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-123", "Item 1", 10, new BigDecimal("10.00"), "Electronics", 5);

        when(inventoryService.addItem(any(), eq("admin"))).thenReturn(sampleItem);

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
        Item updated = new Item(1L, "SKU-123", "Updated Item", 10, new BigDecimal("15.00"), "Home", Instant.now(), false, 5, 1);

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
    void updateItem_notFound_shouldReturn404() throws Exception {
        UpdateItemRequest request = new UpdateItemRequest("Updated Item", new BigDecimal("15.00"), "Home");

        when(inventoryService.updateItem(eq(99L), any())).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/items/99")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void deleteItem_shouldReturn204() throws Exception {
        when(inventoryService.removeItem(1L, "admin")).thenReturn(true);

        mockMvc.perform(delete("/api/items/1")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void deleteItem_notFound_shouldReturn404() throws Exception {
        when(inventoryService.removeItem(99L, "admin")).thenReturn(false);

        mockMvc.perform(delete("/api/items/99")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adjustQuantity_shouldReturn200() throws Exception {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);
        Item updated = new Item(1L, "SKU-123", "Item 1", 15, new BigDecimal("10.00"), "Electronics", Instant.now(), false, 5, 1);

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
    void adjustQuantity_notFound_shouldReturn404() throws Exception {
        AdjustQuantityRequest request = new AdjustQuantityRequest(5);

        when(inventoryService.adjustStock(eq(99L), any(), eq("admin"), eq(null))).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/items/99/quantity")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adjustQuantity_withInvalidPayload_shouldReturn400() throws Exception {
        mockMvc.perform(patch("/api/items/1/quantity")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createItem_withoutAuth_shouldReturn401() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-123", "Item 1", 10, new BigDecimal("10.00"), "Electronics", 5);

        mockMvc.perform(post("/api/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void listItems_shouldReturn200() throws Exception {
        com.inventoryapp.core.dto.CursorResponse<Item> response =
                new com.inventoryapp.core.dto.CursorResponse<>(List.of(sampleItem), null);
        when(inventoryService.listItems(null, 10, null, null)).thenReturn(response);

        mockMvc.perform(get("/api/items"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createItem_withInvalidSku_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
