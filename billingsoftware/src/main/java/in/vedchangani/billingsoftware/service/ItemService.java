package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.ItemRequest;
import in.vedchangani.billingsoftware.io.ItemResponse;
import in.vedchangani.billingsoftware.io.ItemUpdateRequest;
import in.vedchangani.billingsoftware.io.StockAdjustmentRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ItemService {

    ItemResponse add(ItemRequest request, MultipartFile file) throws IOException;

    List<ItemResponse> fetchItems();

    void deleteItem(String itemId);

    ItemResponse update(String itemId, ItemUpdateRequest request);

    ItemResponse adjustStock(String itemId, StockAdjustmentRequest request);
}
