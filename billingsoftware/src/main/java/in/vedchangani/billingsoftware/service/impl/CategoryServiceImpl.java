package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.CategoryRequest;
import in.vedchangani.billingsoftware.io.CategoryResponse;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.CategoryService;
import in.vedchangani.billingsoftware.service.FileUploadService;
import in.vedchangani.billingsoftware.util.UploadUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final FileUploadService fileUploadService;
    private final ItemRepository itemRepository;
    private final AuditService auditService;

    // Public base URL of /uploads/** (configuration, not code - see application.properties).
    @Value("${app.uploads.public-base-url}")
    private String uploadsPublicBaseUrl;

    @Value("${app.uploads.dir}")
    private String uploadsDir;

    // Each mutation is one transaction with its audit event.
    @Override
    @Transactional
    public CategoryResponse add(CategoryRequest request, MultipartFile file) throws IOException {
        //String imgUrl = fileUploadService.uploadFile(file);
        String fileName = UUID.randomUUID().toString()+"."+StringUtils.getFilenameExtension(file.getOriginalFilename());
        Path uploadPath = UploadUrls.directory(uploadsDir);
        Files.createDirectories(uploadPath);
        Path targetLocation = uploadPath.resolve(fileName);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        String imgUrl = UploadUrls.publicUrl(uploadsPublicBaseUrl, fileName);
        CategoryEntity newCategory = convertToEntity(request);
        newCategory.setImgUrl(imgUrl);
        newCategory = categoryRepository.save(newCategory);
        auditService.record(AuditAction.CATEGORY_CREATED, AuditTargetType.CATEGORY, newCategory.getCategoryId(),
                Map.of("name", newCategory.getName()));
        return convertToResponse(newCategory);
    }

    @Override
    public List<CategoryResponse> read() {
        return categoryRepository.findAll()
                .stream()
                .map(categoryEntity -> convertToResponse(categoryEntity))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void delete(String categoryId) {
        CategoryEntity existingCategory = categoryRepository.findByCategoryId(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: "+categoryId));

        // A category with items still assigned to it cannot be deleted (the DB itself enforces
        // this via ON DELETE RESTRICT on tbl_items.category_id) - check for that up front and
        // report it as the business conflict it is, rather than letting the resulting
        // DataIntegrityViolationException be mistaken for "category not found".
        int itemCount = itemRepository.countByCategoryId(existingCategory.getId());
        if (itemCount > 0) {
            throw new ConflictException("Cannot delete category '" + existingCategory.getName()
                    + "' because " + itemCount + " item(s) still reference it");
        }

        //fileUploadService.deleteFile(existingCategory.getImgUrl());
        String imgUrl = existingCategory.getImgUrl();
        String fileName = imgUrl.substring(imgUrl.lastIndexOf("/")+1);
        Path uploadPath = UploadUrls.directory(uploadsDir);
        Path filePath = uploadPath.resolve(fileName);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        categoryRepository.delete(existingCategory);
        auditService.record(AuditAction.CATEGORY_DELETED, AuditTargetType.CATEGORY, categoryId,
                Map.of("name", existingCategory.getName()));
    }

    private CategoryResponse convertToResponse(CategoryEntity newCategory) {
        Integer itemsCount = itemRepository.countByCategoryId(newCategory.getId());
        return CategoryResponse.builder()
                .categoryId(newCategory.getCategoryId())
                .name(newCategory.getName())
                .description(newCategory.getDescription())
                .bgColor(newCategory.getBgColor())
                .imgUrl(newCategory.getImgUrl())
                .createdAt(newCategory.getCreatedAt())
                .updatedAt(newCategory.getUpdatedAt())
                .items(itemsCount)
                .build();
    }

    private CategoryEntity convertToEntity(CategoryRequest request) {
        return CategoryEntity.builder()
                .categoryId(UUID.randomUUID().toString())
                .name(request.getName())
                .description(request.getDescription())
                .bgColor(request.getBgColor())
                .build();
    }
}
