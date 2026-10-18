package com.flashreserve.backend.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class InventoryRepositoryIntegrationTest {

    @Autowired
    private InventoryRepository inventoryRepository;

    private Inventory inventory;

    @BeforeEach
    void setUp() {
        inventory = inventoryRepository.saveAndFlush(
                new Inventory(
                        UUID.randomUUID(),
                        "TEST-" + UUID.randomUUID(),
                        "Repository Test Product",
                        10,
                        10
                )
        );
    }

    @Test
    void reserveQuantityWithSufficientStockDecreasesAvailableQuantity() {
        int updatedRows = inventoryRepository.reserveQuantity(
                inventory.getId(), 3
        );

        assertEquals(1, updatedRows);

        inventoryRepository.flush();

        Inventory updated = inventoryRepository.findById(inventory.getId())
                .orElseThrow();

        assertEquals(7, updated.getAvailableQuantity());
        assertEquals(1, updated.getVersion());
    }

    @Test
    void reserveQuantityWithInsufficientStockDoesNotChangeInventory() {
        int updatedRows = inventoryRepository.reserveQuantity(
                inventory.getId(), 11
        );

        assertEquals(0, updatedRows);

        inventoryRepository.flush();

        Inventory unchanged = inventoryRepository.findById(inventory.getId())
                .orElseThrow();

        assertEquals(10, unchanged.getAvailableQuantity());
        assertEquals(0, unchanged.getVersion());
    }

    @Test
    void reserveQuantityWithZeroQuantityIsRejected() {
        int updatedRows = inventoryRepository.reserveQuantity(
                inventory.getId(), 0
        );

        assertEquals(0, updatedRows);

        inventoryRepository.flush();

        Inventory unchanged = inventoryRepository.findById(inventory.getId())
                .orElseThrow();

        assertEquals(10, unchanged.getAvailableQuantity());
    }

    @Test
    void reserveQuantityWithNegativeQuantityIsRejected() {
        int updatedRows = inventoryRepository.reserveQuantity(
                inventory.getId(), -2
        );

        assertEquals(0, updatedRows);

        inventoryRepository.flush();

        Inventory unchanged = inventoryRepository.findById(inventory.getId())
                .orElseThrow();

        assertEquals(10, unchanged.getAvailableQuantity());
    }

    @Test
    void reserveQuantityForMissingInventoryReturnsZero() {
        int updatedRows = inventoryRepository.reserveQuantity(
                UUID.randomUUID(), 1
        );

        assertEquals(0, updatedRows);
    }
}