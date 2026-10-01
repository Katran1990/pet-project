package dev.katran.pet.quicktemplate;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuickTemplateRepository extends JpaRepository<QuickTemplate, Long> {

	@EntityGraph(attributePaths = "category")
	Optional<QuickTemplate> findWithCategoryById(Long id);

	@EntityGraph(attributePaths = "category")
	List<QuickTemplate> findAllByOrderBySortOrderAscIdAsc();

}
