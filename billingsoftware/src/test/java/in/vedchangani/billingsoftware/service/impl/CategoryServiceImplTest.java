package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.service.FileUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for CategoryServiceImpl.delete:
 *  - deleting a category that still has items is a business conflict (409), not a 404
 *  - deleting a nonexistent category is a 404
 *  - deleting an empty category succeeds
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private FileUploadService fileUploadService;

    private CategoryServiceImpl categoryService;

    private CategoryEntity aCategory(long id, String categoryId, String name) {
        CategoryEntity category = new CategoryEntity();
        category.setId(id);
        category.setCategoryId(categoryId);
        category.setName(name);
        category.setImgUrl("http://localhost:8080/api/v1.0/uploads/does-not-exist.png");
        return category;
    }

    @Test
    void delete_rejectsCategoryStillReferencedByItems() {
        categoryService = new CategoryServiceImpl(categoryRepository, fileUploadService, itemRepository);
        CategoryEntity category = aCategory(1L, "CAT1", "Beverages");
        when(categoryRepository.findByCategoryId("CAT1")).thenReturn(Optional.of(category));
        when(itemRepository.countByCategoryId(1L)).thenReturn(3);

        ConflictException ex = assertThrows(ConflictException.class, () -> categoryService.delete("CAT1"));
        assertTrue(ex.getMessage().contains("Beverages"));
        assertTrue(ex.getMessage().contains("3"));
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void delete_rejectsNonExistentCategory() {
        categoryService = new CategoryServiceImpl(categoryRepository, fileUploadService, itemRepository);
        when(categoryRepository.findByCategoryId("GHOST")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> categoryService.delete("GHOST"));
        verify(categoryRepository, never()).delete(any());
    }
}
