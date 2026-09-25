package dev.katran.pet.web;

public class NotFoundException extends RuntimeException {

	private final String entity;
	private final Object id;

	public NotFoundException(String entity, Object id) {
		super(entity + " not found");
		this.entity = entity;
		this.id = id;
	}

	public String getEntity() {
		return entity;
	}

	public Object getId() {
		return id;
	}

}
