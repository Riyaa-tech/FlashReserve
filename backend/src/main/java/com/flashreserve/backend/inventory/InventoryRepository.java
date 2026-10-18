
package com.flashreserve.backend.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository
        extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findBySku(String sku);

    boolean existsBySku(String sku);

    @Modifying(
        flushAutomatically = true,
        clearAutomatically = true
    )
    @Query("""
            UPDATE Inventory i
            SET i.availableQuantity = i.availableQuantity - :quantity,
                i.version = i.version + 1
            WHERE i.id = :inventoryId
              AND :quantity > 0
              AND i.availableQuantity >= :quantity
            """)
    int reserveQuantity(
            @Param("inventoryId") UUID inventoryId,
            @Param("quantity") int quantity
    );
}
