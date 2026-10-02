package dev.katran.pet.budgetlimit;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.katran.pet.category.ActiveCategoryLookup;
import dev.katran.pet.category.Category;
import dev.katran.pet.web.NotFoundException;

@RestController
@RequestMapping("/api/budget-limits")
public class BudgetLimitController {

	private final BudgetLimitRepository limits;
	private final ActiveCategoryLookup activeCategories;

	public BudgetLimitController(BudgetLimitRepository limits, ActiveCategoryLookup activeCategories) {
		this.limits = limits;
		this.activeCategories = activeCategories;
	}

	@PutMapping
	public BudgetLimitResponse upsert(@Valid @RequestBody BudgetLimitRequest request) {
		Category category = activeCategories.getActive(request.categoryId());
		BudgetLimit limit = limits.findWithCategoryByCategoryIdAndMonth(category.getId(), request.month().atDay(1))
				.orElseGet(() -> new BudgetLimit(category, request.month(), request.amount()));
		limit.setAmount(request.amount());   // no-op for a new instance; the update for an existing one
		limits.saveAndFlush(limit);
		return BudgetLimitResponse.from(limit);
	}

	@GetMapping
	public List<BudgetLimitResponse> list(@Valid @ModelAttribute BudgetLimitListQuery query) {
		return limits.findAllByMonthOrderByCategoryIdAsc(query.month().atDay(1)).stream()
				.map(BudgetLimitResponse::from).toList();
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		BudgetLimit limit = limits.findById(id).orElseThrow(() -> new NotFoundException("Budget limit", id));
		limits.delete(limit);
	}

}
