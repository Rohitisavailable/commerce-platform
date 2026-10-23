# Commerce Platform

Three independently deployable Spring Boot WAR services in one Maven reactor, with a parameterized Jenkins pipeline.

## Services

| Module | Demo endpoints | Tomcat context path |
| --- | --- | --- |
| `auth-service` | `/api/v1/health`, `/api/v1/info` | `/auth-service` |
| `product-service` | `/api/v1/health`, `/api/v1/products`, `/api/v1/products/{sku}` | `/product-service` |
| `order-service` | `/api/v1/health`, `/api/v1/orders`, `/api/v1/orders/{id}` | `/order-service` |

These are intentionally small in-memory examples for demonstrating independent builds and deployments. The authentication endpoint is not a real identity provider, and the product/order data is not persistent.

## Requirements

- JDK 21
- Maven 3.6.3 or later, or a Maven Wrapper added to this repository
- For external WAR deployment: a Servlet 6.0 compatible container such as Tomcat 10.1

This project uses Spring Boot 3.5.16. Spring Boot's documented traditional WAR deployment requires a `SpringBootServletInitializer`, `war` packaging, and a provided servlet container dependency. See [Spring Boot traditional deployment](https://docs.spring.io/spring-boot/3.5/how-to/deployment/traditional-deployment.html) and [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).

## Build

The Maven Wrapper is not included yet. If Maven is installed, run these from the project root:

```powershell
mvn -B clean verify
mvn -B -pl product-service -am clean verify
```

The targeted command selects the product module and any reactor prerequisites. Each app can also be run locally with:

```powershell
mvn -pl product-service spring-boot:run
```

Open `http://localhost:8080/api/v1/health`. To run multiple services on one development machine, assign different ports, for example `SERVER_PORT=8081` for the product service and `SERVER_PORT=8082` for order service. On PowerShell, set the environment variable with `$env:SERVER_PORT = '8081'` before starting the app.

WAR outputs are:

```text
auth-service/target/auth-service.war
product-service/target/product-service.war
order-service/target/order-service.war
```

## Jenkins setup

The root `Jenkinsfile` expects:

1. A Linux agent labeled `linux-maven`, with JDK 21, Maven, Git, `ssh`, `scp`, and `curl`.
2. Jenkins Pipeline, SonarQube Scanner, Config File Provider, and SSH Agent plugins.
3. A configured SonarQube installation named `SonarQube-Server`, with its webhook pointing to Jenkins.
4. A managed Maven settings file with ID `commerce-maven-settings`; its server IDs must match `nexus-releases` and `nexus-snapshots` in the root POM.
5. An SSH private-key credential named `tomcat-deploy-key` and a verified host key for the Tomcat host.
6. A remote deployment helper at `/usr/local/sbin/deploy-commerce-war` that accepts only the three known service names, validates the uploaded WAR, applies ownership safely, and preserves a rollback copy.

Edit the example Tomcat host/user in the Jenkinsfile and the Nexus URLs in `pom.xml`. Keep secrets in Jenkins credentials. Leave `DEPLOY` false for initial pipeline runs. The deployment account should have narrowly scoped permission to invoke the helper; do not give it unrestricted sudo.

The SonarQube stage invokes Maven from the selected child POM so analysis is associated with that service's key. Validate the analysis scope and key in SonarQube before relying on it. The quality gate stage requires a working Jenkins webhook.

## Tomcat URL examples

After deploying the WAR files to the configured Tomcat `appBase`:

```text
http://<tomcat-host>:8080/auth-service/api/v1/health
http://<tomcat-host>:8080/product-service/api/v1/products
http://<tomcat-host>:8080/order-service/api/v1/orders
```

Protect Jenkins, Nexus, and SonarQube management interfaces with private networking and access controls. Do not expose all service and administration ports to the public internet.

## Related guide

See `../enterprise-microservices-delivery-guide.md` for the end-to-end architecture, setup sequence, security considerations, and troubleshooting notes.

For hands-on setup on Windows, follow [LOCAL-DEPLOYMENT.md](LOCAL-DEPLOYMENT.md).
