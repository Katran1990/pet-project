package dev.katran.pet;

import org.springframework.boot.SpringApplication;

public class TestPetApplication {

	public static void main(String[] args) {
		SpringApplication.from(PetApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
