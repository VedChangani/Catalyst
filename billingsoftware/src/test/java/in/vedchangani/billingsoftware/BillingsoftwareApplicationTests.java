package in.vedchangani.billingsoftware;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// "test" activates src/test/resources/application-test.properties (in-memory H2 datasource and
// placeholder secrets) instead of the production MySQL config in
// src/main/resources/application.properties, which is left completely untouched.
@ActiveProfiles("test")
@SpringBootTest
class BillingsoftwareApplicationTests {

	@Test
	void contextLoads() {
	}

}
