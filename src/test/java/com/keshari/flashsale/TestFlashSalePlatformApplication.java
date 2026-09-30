package com.keshari.flashsale;

import org.springframework.boot.SpringApplication;

public class TestFlashSalePlatformApplication {

	public static void main(String[] args) {
		SpringApplication.from(FlashSalePlatformApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
