
package com.flashreserve.backend.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flashreserve.backend.common.DuplicateResourceException;
import com.flashreserve.backend.common.ResourceNotFoundException;

import java.util.List;
import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public Inventory createInventory(
            String sku,
            String name,
            int totalQuantity
    ) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }

        if (totalQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Total quantity must be greater than zero"
            );
        }

        String normalizedSku = sku.trim();

        if (inventoryRepository.existsBySku(normalizedSku)) {
            throw new DuplicateResourceException(
                    "An inventory item with this SKU already exists"
            );
        }

        Inventory inventory = new Inventory(
                UUID.randomUUID(),
                normalizedSku,
                name.trim(),
                totalQuantity,
                totalQuantity
        );

        return inventoryRepository.save(inventory);
    }

    @Transactional(readOnly = true)
    public List<Inventory> getAllInventory() {
        return inventoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Inventory getInventoryById(UUID id) {
        return inventoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inventory item not found: " + id
                ));
    }
}
