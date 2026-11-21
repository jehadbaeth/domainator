package com.domainator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"domainator.providers.porkbun.enabled=false",
		"domainator.providers.ovh.enabled=false",
		"domainator.providers.inwx.enabled=false",
		"domainator.providers.vercel.enabled=false"
})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
