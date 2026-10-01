package dev.katran.pet.quicktemplate;

import dev.katran.pet.category.Category;

// Embedded category of a template. Unlike CategorySummary it carries "archived", because
// applying a template of an archived category returns 409 and clients disable it (card 8).
public record QuickTemplateCategory(Long id, String name, String icon, boolean archived) {

	public static QuickTemplateCategory from(Category category) {
		return new QuickTemplateCategory(category.getId(), category.getName(), category.getIcon(),
				category.isArchived());
	}

}
