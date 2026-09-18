package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.ItemRequest;
import in.vedchangani.billingsoftware.io.ItemResponse;
import in.vedchangani.billingsoftware.io.ItemUpdateRequest;
import in.vedchangani.billingsoftware.io.StockAdjustmentRequest;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.service.FileUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Focused tests for the inventory-related mapping/behavior added to ItemServiceImpl in the admin
 * inventory management batch: create-time mapping (including required stockQuantity, forced
 * reservedQuantity=0, defaulted lowStockThreshold/active), response mapping (availableQuantity),
 * general update (never touches stock, 404, optimistic-lock passthrough), the dedicated
 * adjustStock path (atomic repository call only, never load-mutate-save), and the delete guard
 * against items with reservedQuantity > 0.
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceImplTest {

    @Mock
    private FileUploadService fileUploadService;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ItemRepository itemRepository;

    private ItemServiceImpl itemService;

    private CategoryEntity aCategory() {
        return CategoryEntity.builder().id(1L).categoryId("CAT1").name("Beverages").build();
    }

    private ItemEntity anItem(String itemId, Integer stock, Integer reserved, Integer threshold, Boolean active) {
        return ItemEntity.builder()
                .id(1L)
                .itemId(itemId)
                .name("Burger")
                .price(BigDecimal.valueOf(50))
                .category(aCategory())
                .stockQuantity(stock)
                .reservedQuantity(reserved)
                .lowStockThreshold(threshold)
                .active(active)
                .imgUrl("http://localhost:8080/api/v1.0/uploads/does-not-exist.png")
                .build();
    }

    private MultipartFile aFile() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("photo.png");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        return file;
    }

    // ---- A. create/response mapping ----

    @Test
    void add_mapsSkuStockAndForcesReservedQuantityToZero() throws Exception {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        ItemRequest request = ItemRequest.builder()
                .name("Burger").price(BigDecimal.valueOf(50)).categoryId("CAT1")
                .sku("SKU-1").stockQuantity(20).lowStockThreshold(3).active(false)
                .build();
        when(categoryRepository.findByCategoryId("CAT1")).thenReturn(Optional.of(aCategory()));
        when(itemRepository.save(any(ItemEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ItemResponse response = itemService.add(request, aFile());

        assertEquals("SKU-1", response.getSku());
        assertEquals(20, response.getStockQuantity());
        assertEquals(0, response.getReservedQuantity());
        assertEquals(20, response.getAvailableQuantity());
        assertEquals(3, response.getLowStockThreshold());
        assertEquals(false, response.getActive());

        ArgumentCaptor<ItemEntity> captor = ArgumentCaptor.forClass(ItemEntity.class);
        verify(itemRepository).save(captor.capture());
        assertEquals(0, captor.getValue().getReservedQuantity());
    }

    @Test
    void add_defaultsLowStockThresholdAndActiveWhenOmitted() throws Exception {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        ItemRequest request = ItemRequest.builder()
                .name("Burger").price(BigDecimal.valueOf(50)).categoryId("CAT1")
                .stockQuantity(10)
                .build();
        when(categoryRepository.findByCategoryId("CAT1")).thenReturn(Optional.of(aCategory()));
        when(itemRepository.save(any(ItemEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ItemResponse response = itemService.add(request, aFile());

        assertEquals(true, response.getActive());
        assertNotNull(response.getLowStockThreshold());
        assertTrue(response.getLowStockThreshold() > 0);
        assertEquals(0, response.getReservedQuantity());
    }

    // ---- B. general update ----

    @Test
    void update_updatesMetadataButNeverTouchesStock() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        ItemEntity existing = anItem("ITEM1", 15, 4, 5, true);
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(existing));
        when(itemRepository.save(any(ItemEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ItemUpdateRequest request = ItemUpdateRequest.builder()
                .name("Cheeseburger").active(false).lowStockThreshold(8)
                .build();

        ItemResponse response = itemService.update("ITEM1", request);

        assertEquals("Cheeseburger", response.getName());
        assertEquals(false, response.getActive());
        assertEquals(8, response.getLowStockThreshold());
        // stock untouched by the general update path
        assertEquals(15, response.getStockQuantity());
        assertEquals(4, response.getReservedQuantity());
        assertEquals(11, response.getAvailableQuantity());
    }

    @Test
    void update_rejectsNonExistentItem() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        when(itemRepository.findByItemId("GHOST")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> itemService.update("GHOST", ItemUpdateRequest.builder().build()));
        verify(itemRepository, never()).save(any());
    }

    @Test
    void update_stockQuantityFieldDoesNotExistOnUpdateRequest_soStockCannotChangeThroughGeneralUpdate() {
        // ItemUpdateRequest has no stockQuantity/reservedQuantity setter at all - this is enforced
        // structurally by the DTO, verified here by exercising a full update and confirming stock
        // is unchanged regardless of what metadata was edited.
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        ItemEntity existing = anItem("ITEM1", 15, 4, 5, true);
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(existing));
        when(itemRepository.save(any(ItemEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        itemService.update("ITEM1", ItemUpdateRequest.builder().name("Renamed").build());

        assertEquals(15, existing.getStockQuantity());
        assertEquals(4, existing.getReservedQuantity());
    }

    @Test
    void update_propagatesOptimisticLockingFailureFromSave() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        ItemEntity existing = anItem("ITEM1", 15, 0, 5, true);
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(existing));
        when(itemRepository.save(any(ItemEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(ItemEntity.class, "ITEM1"));

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> itemService.update("ITEM1", ItemUpdateRequest.builder().name("X").build()));
    }

    // ---- C. stock adjustment ----

    @Test
    void adjustStock_positiveDeltaRestocksUsingAtomicQuery() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        when(itemRepository.findByItemId("ITEM1"))
                .thenReturn(Optional.of(anItem("ITEM1", 10, 2, 5, true)))
                .thenReturn(Optional.of(anItem("ITEM1", 15, 2, 5, true)));
        when(itemRepository.adjustStockQuantity("ITEM1", 5)).thenReturn(1);

        ItemResponse response = itemService.adjustStock("ITEM1", StockAdjustmentRequest.builder().delta(5).build());

        assertEquals(15, response.getStockQuantity());
        verify(itemRepository).adjustStockQuantity("ITEM1", 5);
        // never load-mutate-save for stock
        verify(itemRepository, never()).save(any());
    }

    @Test
    void adjustStock_validNegativeCorrectionSucceeds() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        when(itemRepository.findByItemId("ITEM1"))
                .thenReturn(Optional.of(anItem("ITEM1", 10, 2, 5, true)))
                .thenReturn(Optional.of(anItem("ITEM1", 7, 2, 5, true)));
        when(itemRepository.adjustStockQuantity("ITEM1", -3)).thenReturn(1);

        ItemResponse response = itemService.adjustStock("ITEM1", StockAdjustmentRequest.builder().delta(-3).build());

        assertEquals(7, response.getStockQuantity());
    }

    @Test
    void adjustStock_negativeCorrectionBelowReservedQuantityIsConflict() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", 10, 8, 5, true)));
        when(itemRepository.adjustStockQuantity("ITEM1", -5)).thenReturn(0);

        assertThrows(ConflictException.class,
                () -> itemService.adjustStock("ITEM1", StockAdjustmentRequest.builder().delta(-5).build()));
    }

    @Test
    void adjustStock_rejectsNonExistentItemWith404() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        when(itemRepository.findByItemId("GHOST")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> itemService.adjustStock("GHOST", StockAdjustmentRequest.builder().delta(5).build()));
        verify(itemRepository, never()).adjustStockQuantity(any(), anyInt());
    }

    // ---- D. delete safety guard ----

    @Test
    void deleteItem_blockedWhenReservedQuantityPositive() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", 10, 3, 5, true)));

        assertThrows(ConflictException.class, () -> itemService.deleteItem("ITEM1"));
        verify(itemRepository, never()).delete(any());
    }

    @Test
    void deleteItem_allowedWhenReservedQuantityIsZero() {
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        ItemEntity item = anItem("ITEM1", 10, 0, 5, true);
        item.setImgUrl("http://localhost:8080/api/v1.0/uploads/nonexistent-file.png");
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(item));

        itemService.deleteItem("ITEM1");

        verify(itemRepository).delete(item);
    }

    @Test
    void deleteItem_allowedWhenReservedQuantityIsNull() {
        // legacy row from before the inventory batch - reservedQuantity was never backfilled yet.
        itemService = new ItemServiceImpl(fileUploadService, categoryRepository, itemRepository);
        ItemEntity item = anItem("ITEM1", null, null, null, null);
        item.setImgUrl("http://localhost:8080/api/v1.0/uploads/nonexistent-file.png");
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(item));

        itemService.deleteItem("ITEM1");

        verify(itemRepository).delete(item);
    }
}
