package com.aerlon.payment_gateway_sandbox;

import org.springframework.boot.SpringApplication;

public class TestPaymentGatewaySandboxApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentGatewaySandboxApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
