package dev.katran.pet.expense;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

	@EntityGraph(attributePaths = "category")
	Optional<Expense> findWithCategoryById(Long id);

}
