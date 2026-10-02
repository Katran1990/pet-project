package dev.katran.pet.budgetlimit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import dev.katran.pet.category.Category;

@Entity
@Table(name = "budget_limit")
public class BudgetLimit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false, updatable = false)
	private Category category;

	// always the first day of the month (ck_budget_limit_month_first_day)
	@Column(nullable = false, updatable = false)
	private LocalDate month;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	protected BudgetLimit() {
		// for JPA
	}

	public BudgetLimit(Category category, YearMonth month, BigDecimal amount) {
		this.category = category;
		this.month = month.atDay(1);
		this.amount = amount;
	}

	public Long getId() {
		return id;
	}

	public Category getCategory() {
		return category;
	}

	public YearMonth getMonth() {
		return YearMonth.from(month);
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

}
