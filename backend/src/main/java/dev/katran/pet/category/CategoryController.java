package dev.katran.pet.category;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.katran.pet.web.NotFoundException;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

	private final CategoryRepository categories;

	public CategoryController(CategoryRepository categories) {
		this.categories = categories;
	}

	@PostMapping
	public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
		Category saved = categories.saveAndFlush(new Category(request.name(), request.icon()));
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}").buildAndExpand(saved.getId()).toUri();
		return ResponseEntity.created(location).body(CategoryResponse.from(saved));
	}

	@GetMapping
	public List<CategoryResponse> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
		List<Category> found = includeArchived
				? categories.findAllByOrderByIdAsc()
				: categories.findAllByArchivedFalseOrderByIdAsc();
		return found.stream().map(CategoryResponse::from).toList();
	}

	@GetMapping("/{id}")
	public CategoryResponse get(@PathVariable Long id) {
		return categories.findById(id)
				.map(CategoryResponse::from)
				.orElseThrow(() -> new NotFoundException("Category", id));
	}

	@PatchMapping("/{id}")
	public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody UpdateCategoryRequest request) {
		Category category = categories.findById(id)
				.orElseThrow(() -> new NotFoundException("Category", id));
		if (request.name() != null) {
			category.setName(request.name());
		}
		if (request.icon() != null) {
			category.setIcon(request.icon().isEmpty() ? null : request.icon());
		}
		if (request.archived() != null) {
			category.setArchived(request.archived());
		}
		return CategoryResponse.from(categories.saveAndFlush(category));
	}

}
