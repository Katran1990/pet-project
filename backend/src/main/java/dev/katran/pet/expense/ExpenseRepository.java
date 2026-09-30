package dev.katran.pet.expense;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

	// Shared by findPage and totals, so the page and the totals always filter the same rows.
	String LIST_FILTER = """
			where e.spentOn between :from and :to
			  and (:allCategories = true or e.category.id in :categoryIds)
			""";

	@EntityGraph(attributePaths = "category")
	Optional<Expense> findWithCategoryById(Long id);

	@EntityGraph(attributePaths = "category")
	@Query("select e from Expense e " + LIST_FILTER + " order by e.spentOn desc, e.createdAt desc, e.id desc")
	List<Expense> findPage(LocalDate from, LocalDate to, boolean allCategories,
			Collection<Long> categoryIds, Pageable pageable);

	@Query("select new dev.katran.pet.expense.ExpenseTotals(count(e), coalesce(sum(e.amount), 0)) from Expense e "
			+ LIST_FILTER)
	ExpenseTotals totals(LocalDate from, LocalDate to, boolean allCategories, Collection<Long> categoryIds);

}
