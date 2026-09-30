package dev.katran.pet.category;

public record CategorySummary(Long id, String name, String icon) {

	public static CategorySummary from(Category category) {
		return new CategorySummary(category.getId(), category.getName(), category.getIcon());
	}

}
