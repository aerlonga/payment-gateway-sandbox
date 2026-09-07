package com.aerlon.payment_gateway_sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentGatewaySandboxApplicationTests {

	@Test
	void contextLoads() {
	}

}
