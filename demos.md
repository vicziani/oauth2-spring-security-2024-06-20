# OAuth 2.0, implementációja Keycloakkal és Spring Security-vel

## Keycloak indítása

```shell
docker run -d -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin --name keycloak quay.io/keycloak/keycloak:25.0.0 start-dev
```

## Keycloak konfiguráció

* Létre kell hozni egy realmet (`employees`)
* Létre kell hozni egy klienst, amihez meg kell adni annak azonosítóját, és hogy milyen url-en érhető el 
  * _Client ID_: `employees-frontend`
  * _Name_: `Employees Frontend` - pl. a Consent felületen jelenik meg
  * _Root URL_: `http://localhost:8082`
  * _Home URL_: `http://localhost:8082` - pl. az Account Console-on jelenik meg
  * _Valid Redirect URIs_: `http://localhost:8082/*`
* Létre kell hozni a szerepköröket (`employees_user`, `employees_admin`)
* Létre kell hozni egy felhasználót 
  * Username: `johndoe`, ki kell tölteni az _Email_, _First name_, _Last name_ mezőket is
  * _Email Verified_: _On_
  * Beállítani a jelszavát (a _Temporary_ értéke legyen _Off_, hogy ne kelljen jelszót módosítani)
  * Hozzáadni a szerepkört a _Role Mappings_ fülön

## Token lekérése

```http
### OpenID configuration
GET http://localhost:8080/realms/employees/.well-known/openid-configuration
```

```http
### Certificates
GET http://localhost:8080/realms/employees/protocol/openid-connect/certs
```

```http
### Get token with resource owner password credentials
POST http://localhost:8080/realms/employees/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password&client_id=employees-frontend&username=johndoe&password=johndoe
```

## Szerepkör az id tokenbe

* Nincs benne a szerepkör az id tokenben
* Ezért hozzá kell adni: _Client Scopes_/`roles`/_Mappers_/`realm roles`/_Add to ID token_: _On_

## Frontend - függőség

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

## Frontend - Spring Security config

```java
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(registry -> registry
                                .requestMatchers( "/create-employee")
                                .authenticated()
//                                .hasRole("employee_admin")
                                .anyRequest()
                                .permitAll()
                        )
                .oauth2Login(Customizer.withDefaults())
                .logout(logout -> logout
                        .logoutSuccessHandler(oidcLogoutSuccessHandler())
                );
        return http.build();
    }
}
```

## Frontend - application.yaml

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: employees-frontend
            authorization-grant-type: authorization_code
            scope: openid,email,profile
        provider:
          keycloak:
            issuer-uri: http://localhost:8080/realms/employees
            user-name-attribute: preferred_username

```

## PKCE bekapcsolása

```java
public SecurityFilterChain filterChain(HttpSecurity http, ClientRegistrationRepository repo) throws Exception {
        var baseUri = OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(repo, baseUri);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

    // ...
    .oauth2Login(customizer -> customizer.authorizationEndpoint(config -> config.authorizationRequestResolver(resolver)))
    // ...
}
```

## Szerepkörök átvétele

`principal` / `principal` / `idtoken`

* Client Scopes/roles/Mappers/realm roles/Add to ID token
    * A szerepkörök csak ekkor lesznek benne az id tokenbe

* `SecurityConfig`

```java
@Bean
public GrantedAuthoritiesMapper userAuthoritiesMapper() {
    return (authorities) -> authorities.stream().flatMap(authority -> {
        if (authority instanceof OidcUserAuthority oidcUserAuthority) {
            var realmAccess = (Map<String, Object>) oidcUserAuthority.getAttributes().get("realm_access");
            var roles = (List<String>)realmAccess.get("roles");


//                    OidcIdToken idToken = oidcUserAuthority.getIdToken();
//                    OidcUserInfo userInfo = oidcUserAuthority.getUserInfo();

            // Map the claims found in idToken and/or userInfo
            // to one or more GrantedAuthority's and add it to mappedAuthorities
            return roles.stream()
                    .map(roleName -> "ROLE_" + roleName)
                    .map(SimpleGrantedAuthority::new);


        } else if (authority instanceof OAuth2UserAuthority oauth2UserAuthority) {
            Map<String, Object> userAttributes = oauth2UserAuthority.getAttributes();

            // Map the attributes found in userAttributes
            // to one or more GrantedAuthority's and add it to mappedAuthorities
            return Stream.of();
        }
        else if (authority instanceof SimpleGrantedAuthority simpleGrantedAuthority) {
            return Stream.of(simpleGrantedAuthority);
        }
        else {
            throw new IllegalStateException("Invalid authority: %s".formatted(authority.getClass().getName()));
        }
    }).toList();
}
```

# Access token továbbítása a backend felé

* `SecurityConfig`

```java
@Bean
public OAuth2AuthorizedClientManager authorizedClientManager(
        ClientRegistrationRepository clientRegistrationRepository,
        OAuth2AuthorizedClientRepository authorizedClientRepository) {

    OAuth2AuthorizedClientProvider authorizedClientProvider =
            OAuth2AuthorizedClientProviderBuilder.builder()
                    .authorizationCode()
                    .refreshToken()
                    .clientCredentials()
                    .build();

    DefaultOAuth2AuthorizedClientManager authorizedClientManager =
            new DefaultOAuth2AuthorizedClientManager(
                    clientRegistrationRepository, authorizedClientRepository);
    authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

    return authorizedClientManager;
}
```

```java
@Configuration(proxyBeanMethods = false)
public class ClientConfig {
    @Bean
    public EmployeesClient employeesClient(WebClient.Builder builder, EmployeesProperties employeesProperties, OAuth2AuthorizedClientManager authorizedClientManager) {
        var oauth2 = new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultOAuth2AuthorizedClient(true);

        var webClient = builder
                .baseUrl(employeesProperties.getBackendUrl())
                .apply(oauth2.oauth2Configuration())
                .build();
        var factory = HttpServiceProxyFactory
                .builder(WebClientAdapter.forClient(webClient)).build();
        return factory.createClient(EmployeesClient.class);
    }
}
```

## Backend - függőség

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

## Backend - Spring Security Config

```java
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(registry -> registry
                        .requestMatchers(HttpMethod.POST, "/api/employees")
                        .hasRole("employees_admin")
                        .requestMatchers(HttpMethod.PUT, "/api/employees/*")
                        .hasRole("employees_admin")
                        .anyRequest()
                        .permitAll()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(conf -> conf.jwt(Customizer.withDefaults()));
        return http.build();
    }

}
```

## Backend - application.yaml

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/employees
```

# Felhasználónév a backenden

```java
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;

import java.util.Collections;
import java.util.Map;

public class UsernameSubClaimAdapter implements Converter<Map<String, Object>, Map<String, Object>> {

    private final MappedJwtClaimSetConverter delegate = MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());

    @Override
    public Map<String, Object> convert(Map<String, Object> source) {
        Map<String, Object> convertedClaims = this.delegate.convert(source);
        String username = (String) convertedClaims.get("preferred_username");
        convertedClaims.put("sub", username);
        return convertedClaims;
    }
}
```

* `SecurityConfig`

```java
@Bean
public JwtDecoder jwtDecoderByIssuerUri(OAuth2ResourceServerProperties properties) {
    String issuerUri = properties.getJwt().getIssuerUri();
    NimbusJwtDecoder jwtDecoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
    // Use preferred_username from claims as authentication name, instead of UUID subject
    jwtDecoder.setClaimSetConverter(new UsernameSubClaimAdapter());
    return jwtDecoder;
}
```

# Szerepkörök a backenden

```java
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt source) {
        var realmAccess = (Map<String, Object>) source.getClaims().get("realm_access");
        var roles = (List<String>) realmAccess.get("roles");
        return roles.stream()
                .map(roleName -> "ROLE_" + roleName)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
```

* `SecurityConfig`

```java
@Bean
public Converter<Jwt,? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    // Convert realm_access.roles claims to granted authorities, for use in access decisions
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
    return converter;
}
```


## Spring Boot 3.3 támogatás

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/employees
          principal-claim-name: preferred_username
          authorities-claim-name: realm_access_roles
          authority-prefix: ROLE_
```

* Ezért hozzá kell adni: _Client Scopes_/`roles`/_Mappers_/`realm roles`/_Token Claim Mame_: `realm_access_roles`