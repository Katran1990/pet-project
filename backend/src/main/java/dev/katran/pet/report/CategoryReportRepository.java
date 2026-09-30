package dev.katran.pet.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryReportRepository {

	private final JdbcClient jdbc;
	private final String sql;

	public CategoryReportRepository(JdbcClient jdbc,
			@Value("classpath:db/report/by-category.sql") Resource sql) throws IOException {
		this.jdbc = jdbc;
		this.sql = sql.getContentAsString(StandardCharsets.UTF_8);
	}

	public List<CategoryReportLine> findByMonth(YearMonth month) {
		return jdbc.sql(sql)
				.param("month", month.atDay(1))
				.query(CategoryReportLine.class)
				.list();
	}

}
