package com.domainator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"domainator.cache-dir=",
		"domainator.providers.porkbun.enabled=false",
		"domainator.providers.ovh.enabled=false",
		"domainator.providers.inwx.enabled=false",
		"domainator.providers.vercel.enabled=false",
		"domainator.providers.nfs.enabled=false",
		"domainator.providers.namebright.enabled=false",
		"domainator.providers.domainfactory.enabled=false",
		"domainator.providers.onecom.enabled=false",
		"domainator.providers.allinkl.enabled=false",
		"domainator.providers.loopia.enabled=false",
		"domainator.providers.namespro.enabled=false",
		"domainator.providers.eurodns.enabled=false",
		"domainator.providers.101domain.enabled=false",
		"domainator.providers.gname.enabled=false",
		"domainator.providers.uniteddomains.enabled=false"
})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
