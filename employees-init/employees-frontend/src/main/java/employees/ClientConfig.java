package employees;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmployeesProperties.class)
public class ClientConfig {
    @Bean
    public EmployeesClient employeesClient(WebClient.Builder builder, EmployeesProperties employeesProperties) {

        var webClient = builder
                .baseUrl(employeesProperties.getBackendUrl())
                .build();
        var factory = HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(webClient)).build();

        return factory.createClient(EmployeesClient.class);
    }
}
