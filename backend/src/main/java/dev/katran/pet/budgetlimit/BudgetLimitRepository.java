package dev.katran.pet.budgetlimit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetLimitRepository extends JpaRepository<BudgetLimit, Long> {

	@EntityGraph(attributePaths = "category")
	Optional<BudgetLimit> findWithCategoryByCategoryIdAndMonth(Long categoryId, LocalDate month);

	@EntityGraph(attributePaths = "category")
	List<BudgetLimit> findAllByMonthOrderByCategoryIdAsc(LocalDate month);

}
