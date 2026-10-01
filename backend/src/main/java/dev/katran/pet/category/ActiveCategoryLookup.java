package dev.katran.pet.category;

import org.springframework.stereotype.Component;

import dev.katran.pet.web.ConflictException;
import dev.katran.pet.web.InvalidFieldException;

// Resolves a categoryId from a request to a category that may receive new data
// (expenses, budget limits, quick templates).
@Component
public class ActiveCategoryLookup {

	private final CategoryRepository categories;

	public ActiveCategoryLookup(CategoryRepository categories) {
		this.categories = categories;
	}

	// 400 errors[categoryId] for an unknown id; 409 for an archived category.
	public Category getActive(Long categoryId) {
		Category category = categories.findById(categoryId)
				.orElseThrow(() -> new InvalidFieldException("categoryId", "Category not found"));
		return requireNotArchived(category);
	}

	public Category requireNotArchived(Category category) {
		if (category.isArchived()) {
			throw new ConflictException("Category is archived");
		}
		return category;
	}

}
