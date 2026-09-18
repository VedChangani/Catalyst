package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.io.ItemRequest;
import in.vedchangani.billingsoftware.io.ItemResponse;
import in.vedchangani.billingsoftware.io.ItemUpdateRequest;
import in.vedchangani.billingsoftware.io.StockAdjustmentRequest;
import in.vedchangani.billingsoftware.service.ItemService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;
    private final Validator validator;

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/admin/items")
    public ItemResponse addItem(@RequestPart("item") String itemString,
                                @RequestPart("file") MultipartFile file) {
        ObjectMapper objectMapper = new ObjectMapper();
        ItemRequest itemRequest;
        try {
            itemRequest = objectMapper.readValue(itemString, ItemRequest.class);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed item payload: "+ex.getMessage());
        }

        Set<ConstraintViolation<ItemRequest>> violations = validator.validate(itemRequest);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An image file is required");
        }

        try {
            return itemService.add(itemRequest, file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/items")
    public List<ItemResponse> readItems() {
        return itemService.fetchItems();
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/admin/items/{itemId}")
    public void removeItem(@PathVariable String itemId) {
        itemService.deleteItem(itemId);
    }

    @PutMapping("/admin/items/{itemId}")
    public ItemResponse updateItem(@PathVariable String itemId, @Valid @RequestBody ItemUpdateRequest request) {
        return itemService.update(itemId, request);
    }

    @PatchMapping("/admin/items/{itemId}/stock")
    public ItemResponse adjustStock(@PathVariable String itemId, @Valid @RequestBody StockAdjustmentRequest request) {
        return itemService.adjustStock(itemId, request);
    }
}
