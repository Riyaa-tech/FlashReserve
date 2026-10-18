
package com.flashreserve.backend.inventory;

import com.flashreserve.backend.common.DuplicateResourceException;
import com.flashreserve.backend.common.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private InventoryService inventoryService;

    @Test
    void createInventoryWithValidInputSavesInventory() {
        when(inventoryRepository.existsBySku("FLASH-001"))
                .thenReturn(false);

        when(inventoryRepository.save(any(Inventory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Inventory result = inventoryService.createInventory(
                " FLASH-001 ", " Demo Product ", 100
        );

        assertEquals("FLASH-001", result.getSku());
        assertEquals("Demo Product", result.getName());
        assertEquals(100, result.getTotalQuantity());
        assertEquals(100, result.getAvailableQuantity());
        assertNotNull(result.getId());

        verify(inventoryRepository).save(any(Inventory.class));
    }

    @Test
    void createInventoryWithBlankSkuThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> inventoryService.createInventory("", "Demo Product", 100));

        verify(inventoryRepository, never()).save(any(Inventory.class));
    }

    @Test
    void createInventoryWithBlankNameThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> inventoryService.createInventory("FLASH-001", " ", 100));

        verify(inventoryRepository, never()).save(any(Inventory.class));
    }

    @Test
    void createInventoryWithZeroQuantityThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> inventoryService.createInventory("FLASH-001", "Demo Product", 0));

        verify(inventoryRepository, never()).save(any(Inventory.class));
    }

    @Test
    void createInventoryWithNegativeQuantityThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> inventoryService.createInventory("FLASH-001", "Demo Product", -5));

        verify(inventoryRepository, never()).save(any(Inventory.class));
    }

    @Test
    void createInventoryWithDuplicateSkuThrowsException() {
        when(inventoryRepository.existsBySku("FLASH-001"))
                .thenReturn(true);

        assertThrows(DuplicateResourceException.class,
                () -> inventoryService.createInventory(
                        "FLASH-001", "Demo Product", 100
                ));

        verify(inventoryRepository, never()).save(any(Inventory.class));
    }

    @Test
    void getInventoryByIdReturnsInventoryWhenItExists() {
        UUID id = UUID.randomUUID();

        Inventory expected = new Inventory(
                id, "FLASH-001", "Demo Product", 100, 100
        );

        when(inventoryRepository.findById(id))
                .thenReturn(Optional.of(expected));

        Inventory result = inventoryService.getInventoryById(id);

        assertSame(expected, result);
        verify(inventoryRepository).findById(id);
    }

    @Test
    void getInventoryByIdThrowsExceptionWhenMissing() {
        UUID id = UUID.randomUUID();

        when(inventoryRepository.findById(id))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> inventoryService.getInventoryById(id));

        verify(inventoryRepository).findById(id);
    }

    @Test
    void getAllInventoryReturnsAllRecords() {
        List<Inventory> expected = List.of(
                new Inventory(UUID.randomUUID(), "FLASH-001",
                        "Product One", 100, 100),
                new Inventory(UUID.randomUUID(), "FLASH-002",
                        "Product Two", 50, 50)
        );

        when(inventoryRepository.findAll()).thenReturn(expected);

        List<Inventory> result = inventoryService.getAllInventory();

        assertEquals(2, result.size());
        assertEquals(expected, result);
        verify(inventoryRepository).findAll();
    }
}
