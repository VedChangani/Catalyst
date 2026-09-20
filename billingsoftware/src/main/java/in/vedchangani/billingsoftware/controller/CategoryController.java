package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.io.CategoryRequest;
import in.vedchangani.billingsoftware.io.CategoryResponse;
import in.vedchangani.billingsoftware.service.CategoryService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
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
public class CategoryController {

    private final CategoryService categoryService;
    private final Validator validator;

    @PostMapping("/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse addCategory(@RequestPart("category") String categoryString,
                                        @RequestPart("file") MultipartFile file) {
        ObjectMapper objectMapper = new ObjectMapper();
        CategoryRequest request;
        try {
            request = objectMapper.readValue(categoryString, CategoryRequest.class);
        } catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed category payload: "+ex.getMessage());
        }

        Set<ConstraintViolation<CategoryRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An image file is required");
        }

        try {
            return categoryService.add(request, file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/categories")
    public List<CategoryResponse> fetchCategories() {
        return categoryService.read();
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/admin/categories/{categoryId}")
    public void remove(@PathVariable String categoryId) {
        // Not-found and conflict (category still referenced by items) are distinct outcomes -
        // CategoryServiceImpl throws the corresponding typed exception and GlobalExceptionHandler
        // maps each to its own status (404 / 409) rather than collapsing both into 404.
        categoryService.delete(categoryId);
    }
}
