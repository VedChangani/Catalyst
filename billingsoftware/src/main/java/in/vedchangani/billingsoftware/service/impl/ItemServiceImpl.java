package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.ItemRequest;
import in.vedchangani.billingsoftware.io.ItemResponse;
import in.vedchangani.billingsoftware.io.ItemUpdateRequest;
import in.vedchangani.billingsoftware.io.StockAdjustmentRequest;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.FileUploadService;
import in.vedchangani.billingsoftware.util.UploadUrls;
import in.vedchangani.billingsoftware.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    // Applied when ItemRequest/ItemUpdateRequest omit lowStockThreshold - a sensible admin
    // default, not a business-critical constant, so it's kept local rather than configurable.
    private static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;

    private final FileUploadService fileUploadService;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final AuditService auditService;

    // Public base URL of /uploads/** (configuration, not code - see application.properties).
    @Value("${app.uploads.public-base-url}")
    private String uploadsPublicBaseUrl;

    @Value("${app.uploads.dir}")
    private String uploadsDir;

    // Every mutation below is one transaction with its audit event, so a failed change leaves no
    // "succeeded" record behind.
    @Override
    @Transactional
    public ItemResponse add(ItemRequest request, MultipartFile file) throws IOException {
        //String imgUrl = fileUploadService.uploadFile(file);
        String fileName = UUID.randomUUID().toString()+"."+ StringUtils.getFilenameExtension(file.getOriginalFilename());
        Path uploadPath = UploadUrls.directory(uploadsDir);
        Files.createDirectories(uploadPath);
        Path targetLocation = uploadPath.resolve(fileName);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        String imgUrl = UploadUrls.publicUrl(uploadsPublicBaseUrl, fileName);
        ItemEntity newItem = convertToEntity(request);
        CategoryEntity existingCategory = categoryRepository.findByCategoryId(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: "+request.getCategoryId()));
        newItem.setCategory(existingCategory);
        newItem.setImgUrl(imgUrl);
        newItem = itemRepository.save(newItem);
        auditService.record(AuditAction.ITEM_CREATED, AuditTargetType.ITEM, newItem.getItemId(),
                Map.of("name", newItem.getName(), "stockQuantity", newItem.getStockQuantity()));
        return convertToResponse(newItem);
    }

    private ItemResponse convertToResponse(ItemEntity newItem) {
        Integer stockQuantity = newItem.getStockQuantity();
        Integer reservedQuantity = newItem.getReservedQuantity();
        Integer availableQuantity = (stockQuantity != null && reservedQuantity != null)
                ? stockQuantity - reservedQuantity
                : null;

        return ItemResponse.builder()
                .itemId(newItem.getItemId())
                .name(newItem.getName())
                .description(newItem.getDescription())
                .price(newItem.getPrice())
                .imgUrl(newItem.getImgUrl())
                .categoryName(newItem.getCategory().getName())
                .categoryId(newItem.getCategory().getCategoryId())
                .createdAt(newItem.getCreatedAt())
                .updatedAt(newItem.getUpdatedAt())
                .sku(newItem.getSku())
                .stockQuantity(stockQuantity)
                .reservedQuantity(reservedQuantity)
                .availableQuantity(availableQuantity)
                .lowStockThreshold(newItem.getLowStockThreshold())
                .active(newItem.getActive())
                .build();
    }

    private ItemEntity convertToEntity(ItemRequest request) {
        return ItemEntity.builder()
                .itemId(UUID.randomUUID().toString())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .sku(request.getSku())
                // stockQuantity is required on ItemRequest and is never defaulted.
                .stockQuantity(request.getStockQuantity())
                // Never client-supplied - every new item starts with nothing reserved.
                .reservedQuantity(0)
                .lowStockThreshold(request.getLowStockThreshold() != null
                        ? request.getLowStockThreshold()
                        : DEFAULT_LOW_STOCK_THRESHOLD)
                .active(request.getActive() != null ? request.getActive() : true)
                .build();
    }

    @Override
    public List<ItemResponse> fetchItems() {
        return itemRepository.findAll()
                .stream()
                .map(itemEntity -> convertToResponse(itemEntity))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteItem(String itemId) {
        ItemEntity existingItem = itemRepository.findByItemId(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: "+itemId));

        // A PENDING_PAYMENT order may hold a reservation against this item; deleting it here
        // would leave that order with no ItemEntity left to commit/release the reservation
        // against later. active=false is the correct "stop selling" lever instead.
        if (existingItem.getReservedQuantity() != null && existingItem.getReservedQuantity() > 0) {
            throw new ConflictException("Cannot delete item " + itemId + ": it has " +
                    existingItem.getReservedQuantity() + " unit(s) reserved by in-progress orders");
        }

        // The row is deleted FIRST, by a statement that reports how many rows it removed. (Not
        // itemRepository.delete(entity): for an item whose @Version is NULL - every item created
        // before inventory tracking - Spring Data treats the entity as new and skips the delete
        // without any SQL, while this method went on to answer 204.) A count of 0 is never a success.
        int deleted = itemRepository.deleteUnreservedById(existingItem.getId());
        if (deleted != 1) {
            // Not there any more, or a reservation appeared since the check above.
            throw new ConflictException("Item " + itemId + " could not be deleted: it was changed or reserved by an "
                    + "in-progress order. Refresh and try again.");
        }
        auditService.record(AuditAction.ITEM_DELETED, AuditTargetType.ITEM, itemId,
                Map.of("name", existingItem.getName()));

        // Only once the row is gone: remove the image file. If that fails the exception rolls the
        // deletion back, so the item and its image are never left half-removed.
        String imgUrl = existingItem.getImgUrl();
        if (imgUrl != null && !imgUrl.isBlank()) {
            String fileName = imgUrl.substring(imgUrl.lastIndexOf("/") + 1);
            Path filePath = UploadUrls.directory(uploadsDir).resolve(fileName);
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to delete the image");
            }
        }
    }

    @Override
    @Transactional
    public ItemResponse update(String itemId, ItemUpdateRequest request) {
        ItemEntity existingItem = itemRepository.findByItemId(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: "+itemId));

        if (request.getName() != null) {
            existingItem.setName(request.getName());
        }
        if (request.getPrice() != null) {
            existingItem.setPrice(request.getPrice());
        }
        if (request.getCategoryId() != null) {
            CategoryEntity category = categoryRepository.findByCategoryId(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: "+request.getCategoryId()));
            existingItem.setCategory(category);
        }
        if (request.getDescription() != null) {
            existingItem.setDescription(request.getDescription());
        }
        if (request.getSku() != null) {
            existingItem.setSku(request.getSku());
        }
        if (request.getLowStockThreshold() != null) {
            existingItem.setLowStockThreshold(request.getLowStockThreshold());
        }
        if (request.getActive() != null) {
            existingItem.setActive(request.getActive());
        }
        // stockQuantity/reservedQuantity are intentionally never touched here - ItemUpdateRequest
        // has no such fields; stock only changes through adjustStock's atomic query.

        ItemEntity saved = itemRepository.save(existingItem);
        // which fields the admin submitted - names only
        List<String> fields = new ArrayList<>();
        if (request.getName() != null) fields.add("name");
        if (request.getPrice() != null) fields.add("price");
        if (request.getCategoryId() != null) fields.add("category");
        if (request.getDescription() != null) fields.add("description");
        if (request.getSku() != null) fields.add("sku");
        if (request.getLowStockThreshold() != null) fields.add("lowStockThreshold");
        if (request.getActive() != null) fields.add("active");
        auditService.record(AuditAction.ITEM_UPDATED, AuditTargetType.ITEM, itemId, Map.of("fields", fields));
        return convertToResponse(saved);
    }

    @Override
    @Transactional
    public ItemResponse adjustStock(String itemId, StockAdjustmentRequest request) {
        // Confirmed to exist first so a missing item reports 404, not the 409 used for a
        // rejected-but-existing adjustment.
        itemRepository.findByItemId(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: "+itemId));

        // Atomic conditional UPDATE - never load stockQuantity into Java, mutate it, and save.
        int updated = itemRepository.adjustStockQuantity(itemId, request.getDelta());
        if (updated == 0) {
            throw new ConflictException("Cannot adjust stock for item " + itemId +
                    ": the requested change would leave stockQuantity below reservedQuantity");
        }

        ItemEntity reloaded = itemRepository.findByItemId(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: "+itemId));
        // only reached when the guarded UPDATE actually changed the row
        auditService.record(AuditAction.INVENTORY_ADJUSTED, AuditTargetType.ITEM, itemId,
                Map.of("delta", request.getDelta(), "stockQuantity", reloaded.getStockQuantity()));
        return convertToResponse(reloaded);
    }
}
