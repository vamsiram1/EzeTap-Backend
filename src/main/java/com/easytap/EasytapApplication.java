package com.easytap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EasytapApplication {

	public static void main(String[] args) {
		SpringApplication.run(EasytapApplication.class, args);
	}

}
