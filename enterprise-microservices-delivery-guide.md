# Enterprise Microservices Delivery Platform

## In-depth implementation guide

This guide turns the supplied outline into a practical reference project: three independently deployable Spring Boot services in one Maven repository, delivered through a parameterized Jenkins pipeline to a shared Tomcat host. It explains the build, analysis, artifact publication, deployment, verification, and operational boundaries.

> **Scope:** This is a learning/demo architecture. One EC2 instance running Jenkins, SonarQube, Nexus, and Tomcat is convenient for a lab, but it is a single point of failure and is not a production topology. Keep management interfaces private and use least-privilege credentials even in a demo.

## 1. What the platform does

The repository contains three application modules:

| Service | Maven module | Example context path | SonarQube key |
| --- | --- | --- | --- |
| Authentication | `auth-service` | `/auth-service` | `commerce-auth` |
| Product catalog | `product-service` | `/product-service` | `commerce-prod` |
| Orders | `order-service` | `/order-service` | `commerce-order` |

One Jenkins pipeline takes a service choice, checks out the repository, builds the selected module and its reactor prerequisites, runs static analysis, publishes the artifact to Nexus, and deploys the selected WAR to Tomcat. The deployment is independent: releasing `product-service` does not require deploying the other two services.

The Maven reactor's `-pl` option selects projects and `-am` also builds their required reactor projects. It does not mean Maven secretly skips every other module after configuring a full build; it selects a reactor subset. [Maven reactor guide](https://maven.apache.org/guides/mini/guide-multiple-modules.html)

## 2. Architecture and request flow

```text
Developer push
     |
     v
Git repository ----> Jenkins agent
                         |  Maven build + tests
                         |  SonarQube analysis
                         |  Nexus artifact publication
                         v
                    versioned WAR
                         |
                   approved deploy
                         v
                  Tomcat webapps
                    /     |     \
             auth-service product-service order-service
```

For production, normally separate the CI server, artifact repository, analysis service, and runtime hosts. Put an ingress/reverse proxy in front of applications, use TLS, isolate management services on private networks, and add health checks, backups, monitoring, and a rollback strategy.

## 3. Prerequisites

### Developer and Jenkins build environment

- Git repository access.
- A supported JDK installed on the Jenkins build agent. Use the same Java release for Maven compilation and runtime where practical.
- Maven Wrapper checked into the repository (`mvnw`, `.mvn/wrapper/`) so local and CI builds use a pinned Maven distribution.
- Jenkins Pipeline support, Git integration, credentials binding, and SonarQube integration. Install the SSH Agent plugin only if the sample pipeline uses its `sshagent` step.
- A reachable SonarQube server and Nexus repository.
- A Tomcat host reachable from the deployment agent over SSH.

Pin compatible Java, Spring Boot, Tomcat, Maven, Jenkins plugin, SonarQube, and Nexus versions as part of the implementation. Do not copy a version number from a tutorial without checking that it remains supported and compatible with the selected runtime.

### Network access

Do not make every port public. Prefer a private subnet, VPN, or restricted administrator CIDRs for Jenkins, SonarQube, and Nexus. A typical lab network policy is:

| Port | Service | Recommended access |
| --- | --- | --- |
| 22 | SSH | Administrator IPs and the Jenkins deployment agent only |
| 8080 | Tomcat HTTP | Private test clients or a reverse proxy; use TLS at ingress |
| 8081 | Nexus Repository Manager | Jenkins agents and administrators only |
| 9000 | SonarQube | Jenkins agents and administrators only |
| 8080 or a configured alternate | Jenkins HTTP | Administrators only, preferably behind TLS/reverse proxy |

Jenkins and Tomcat commonly both default to port 8080, so they cannot bind that port on the same host. Assign different ports or, preferably, separate the hosts. A security-group rule is not an application authentication mechanism.

## 4. Repository layout

```text
commerce-platform/
├── .mvn/wrapper/
├── mvnw
├── mvnw.cmd
├── pom.xml
├── Jenkinsfile
├── README.md
├── auth-service/
│   ├── pom.xml
│   └── src/
├── product-service/
│   ├── pom.xml
│   └── src/
└── order-service/
    ├── pom.xml
    └── src/
```

Keep shared build conventions in the parent POM. Keep service-specific runtime dependencies, configuration, endpoints, and tests in each child module. Avoid turning the parent into a shared runtime library; if real code is reused, add a deliberate library module and treat it as a dependency.

## 5. Maven multi-module configuration

### 5.1 Parent POM

The root POM aggregates the modules and centralizes versions and plugin defaults. This example uses Spring Boot's parent for dependency and plugin management; if the organization already has a corporate parent POM, import the Spring Boot BOM in `dependencyManagement` instead because Maven permits only one parent.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.0</version><!-- Replace with a supported, tested version -->
    <relativePath/>
  </parent>

  <groupId>com.commerce</groupId>
  <artifactId>commerce-platform</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>auth-service</module>
    <module>product-service</module>
    <module>order-service</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>${java.version}</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <distributionManagement>
    <repository>
      <id>nexus-releases</id>
      <url>https://nexus.example.invalid/repository/maven-releases/</url>
    </repository>
    <snapshotRepository>
      <id>nexus-snapshots</id>
      <url>https://nexus.example.invalid/repository/maven-snapshots/</url>
    </snapshotRepository>
  </distributionManagement>
</project>
```

Replace the illustrative Boot version and Nexus hostname with versions and addresses verified for your environment. Maven requires `<parent>` before the child project's coordinates. If the organization already has a corporate parent POM, use that as the parent and import the Spring Boot BOM through `dependencyManagement` instead.

Add release and snapshot repository URLs in `distributionManagement`; Maven credentials belong in the Jenkins agent's temporary or managed `settings.xml`, never in the POM or Git. Each server `<id>` in Maven settings must match the corresponding repository `<id>`.

### 5.2 Child POMs

Each web service is a WAR so it can be installed into an external Tomcat. A Spring Boot application deployed this way should extend `SpringBootServletInitializer` and mark the embedded Tomcat dependency as `provided`.

Example `product-service/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.commerce</groupId>
    <artifactId>commerce-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>product-service</artifactId>
  <packaging>war</packaging>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>${project.artifactId}</finalName>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

Use the same structure for `auth-service` and `order-service`, changing only the artifact ID and service-specific dependencies. `finalName` makes the output `target/product-service.war`; without it Maven normally includes the project version in the filename. Spring Boot 3 uses Jakarta APIs. Confirm the selected Boot and Tomcat major versions are compatible; for example, Tomcat 10 uses `jakarta.*`, while older Tomcat 9 applications generally use `javax.*`. [Tomcat version and compatibility information](https://tomcat.apache.org/whichversion.html)

### 5.3 Application bootstrap and context path

```java
package com.commerce.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
public class ProductApplication extends SpringBootServletInitializer {
    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(ProductApplication.class);
    }
}
```

When Tomcat deploys `product-service.war`, its default context path is `/product-service`. A small endpoint can be reached at `http://host:8080/product-service/health` if the application provides that route. Add a health endpoint intentionally; do not assume every Spring Boot management endpoint is enabled or exposed by default.

## 6. Run and build locally

From the repository root:

```bash
./mvnw -version
./mvnw -pl product-service -am clean verify
```

On Windows, use `mvnw.cmd`. `verify` runs the lifecycle through verification, including configured tests and checks. To package one module without reactor prerequisites, use `-pl product-service`; if a module depends on another module in this same repository, include `-am` so Maven builds the prerequisite too.

Expected artifact:

```text
product-service/target/product-service.war
```

Run the full reactor before merging a shared change:

```bash
./mvnw clean verify
```

Do not use `-DskipTests` in the normal validation path. If a separate packaging or deployment stage deliberately skips tests, make sure the same commit already passed the test stage and preserve that evidence in the pipeline.

## 7. Configure SonarQube project isolation

Create the three SonarQube projects and their keys. Store the analysis token in Jenkins Credentials, and configure a named SonarQube installation in Jenkins (for example, `SonarQube-Server`). The SonarQube Jenkins integration supplies `withSonarQubeEnv` and can associate an analysis with a later quality gate check.

For separate project histories, set the key for the selected service at analysis time. A Maven command such as:

```bash
./mvnw -B -pl product-service -am \
  org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.projectKey=commerce-prod
```

must be adapted to the scanner and SonarQube versions you actually support. Keep analysis focused on the selected service's sources; reactor prerequisites may be required for compilation but should not accidentally become separate service analyses. Check the scanner's current multi-module behavior and configure source/test paths and project key deliberately. A single scanner execution against a multi-module reactor can have different semantics than one isolated service project, so verify the resulting project in SonarQube before claiming isolation.

If robust per-service isolation is a requirement, the cleanest pattern is to run analysis from the selected child module after its dependencies are available, or define a dedicated service-level analysis configuration that excludes unrelated modules. Confirm that the SonarQube UI shows the intended key and source set. Add `waitForQualityGate()` after analysis only after configuring the SonarQube webhook to Jenkins; use a timeout so a missing callback cannot stall builds forever.

## 8. Configure Nexus publication

Create hosted Maven release and snapshot repositories in Nexus. Configure the repository URLs in `distributionManagement` and provision matching server credentials through Jenkins-managed Maven settings. Use TLS and a dedicated deploy identity with permission only to publish to the intended repositories.

Maven's `deploy` lifecycle publishes project artifacts; it does not upload only a WAR unless the module is configured that way, and `-pl ... -am` can include reactor prerequisites in the selected build. Decide whether parent POMs and prerequisite modules should also be published. Avoid publishing snapshots as releases. Prefer immutable release coordinates and deploy from a tagged or otherwise controlled revision.

Example settings file structure (put real credentials into Jenkins credential bindings, not source control):

```xml
<settings>
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>${env.NEXUS_USER}</username>
      <password>${env.NEXUS_PASSWORD}</password>
    </server>
    <server>
      <id>nexus-snapshots</id>
      <username>${env.NEXUS_USER}</username>
      <password>${env.NEXUS_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Credential interpolation depends on how Maven settings are generated and the Maven version/configuration. In practice, use Jenkins Config File Provider or create a temporary `settings.xml` in a credential-bound shell step; do not assume Maven automatically expands arbitrary environment variables in every settings field. Restrict file permissions and delete temporary settings in a `finally`/cleanup path.

## 9. Prepare Tomcat deployment

Install a supported Java runtime and Tomcat on the runtime host. Run Tomcat under a dedicated unprivileged OS account, keep its management interfaces private, and give the deployment account permission only to update application files or invoke a narrowly scoped deployment helper. Do not run Jenkins as root or grant its SSH account general sudo access.

For a lab, SCP to a temporary path and atomically move the WAR into the configured appBase (often `webapps`). Confirm Tomcat's `appBase`, service account, ownership, and service manager before selecting paths; `/opt/tomcat/webapps` is only an example. Check the Tomcat logs and health endpoint after deployment. Replacing a WAR may trigger redeployment, but do not rely on that behavior as a health check or as an atomic rollback mechanism.

For more controlled deployments, use Tomcat Manager with a narrowly scoped role, a deployment script that validates and atomically swaps artifacts, or deploy an immutable container image. Retain the previous known-good artifact and document rollback steps.

## 10. Jenkins configuration

### 10.1 Configure Jenkins once

1. Create a Pipeline job whose definition comes from SCM, or a Multibranch Pipeline.
2. Install only required plugins: Pipeline, Git, Credentials Binding, SonarQube integration, and optionally SSH Agent / Config File Provider.
3. Configure the JDK and Maven Wrapper or a named Maven tool on the agent.
4. Add Jenkins credentials for Git read access if the repository is private; Nexus publish access; SonarQube analysis; and the Tomcat SSH deployment key.
5. Configure the SonarQube server name in Jenkins and its webhook back to Jenkins if using quality gate waits.
6. Restrict who can run deployment builds and who can change the Jenkinsfile. A pipeline that can use production credentials is trusted code.
7. Configure a known-hosts entry for the Tomcat host. Do not disable SSH host-key checking in the pipeline.

Jenkins documents parameters in the `parameters` directive and credential scoping through credentials bindings. Secret masking reduces accidental disclosure but is not a boundary against malicious pipeline code. [Jenkins Pipeline and credentials guidance](https://www.jenkins.io/doc/book/pipeline/jenkinsfile/)

### 10.2 Example declarative Jenkinsfile

This example is a starting point. Replace tool names, credential IDs, repository URL, host, target path, and SonarQube invocation with the values you have verified. The credentials in the example are intentionally referenced by ID rather than embedded in the file.

```groovy
pipeline {
    agent { label 'linux-maven' }

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    parameters {
        choice(name: 'SERVICE_NAME',
               choices: ['auth-service', 'product-service', 'order-service'],
               description: 'Service to build, publish, and deploy')
        booleanParam(name: 'DEPLOY', defaultValue: false,
                     description: 'Deploy after validation and publication')
    }

    environment {
        SONAR_SERVER = 'SonarQube-Server'
        TOMCAT_HOST = 'tomcat.example.internal'
        TOMCAT_USER = 'commerce-deploy'
        TOMCAT_APPBASE = '/opt/tomcat/webapps'
    }

    stages {
        stage('Validate selection') {
            steps {
                script {
                    def allowed = ['auth-service', 'product-service', 'order-service']
                    if (!allowed.contains(params.SERVICE_NAME)) {
                        error("Unsupported service selection")
                    }
                }
            }
        }

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build and test') {
            steps {
                sh './mvnw -B -pl "${SERVICE_NAME}" -am clean verify'
                archiveArtifacts artifacts: "${SERVICE_NAME}/target/${SERVICE_NAME}.war",
                                 fingerprint: true
            }
        }

        stage('SonarQube analysis') {
            steps {
                script {
                    def sonarKeys = [
                        'auth-service': 'commerce-auth',
                        'product-service': 'commerce-prod',
                        'order-service': 'commerce-order'
                    ]
                    withSonarQubeEnv(env.SONAR_SERVER) {
                        sh """./mvnw -B -pl '${params.SERVICE_NAME}' -am \\
                          org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \\
                          -Dsonar.projectKey='${sonarKeys[params.SERVICE_NAME]}'"""
                    }
                }
            }
        }

        stage('Quality gate') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Publish to Nexus') {
            steps {
                configFileProvider([configFile(fileId: 'commerce-maven-settings',
                                               variable: 'MAVEN_SETTINGS')]) {
                    sh './mvnw -B -s "$MAVEN_SETTINGS" -pl "${SERVICE_NAME}" -am deploy -DskipTests'
                }
            }
        }

        stage('Deploy to Tomcat') {
            when { expression { return params.DEPLOY } }
            steps {
                sshagent(credentials: ['tomcat-deploy-key']) {
                    sh '''#!/bin/sh
                        set -eu
                        artifact="${SERVICE_NAME}/target/${SERVICE_NAME}.war"
                        test -f "$artifact"
                        scp -o BatchMode=yes "$artifact" \
                          "$TOMCAT_USER@$TOMCAT_HOST:/tmp/${SERVICE_NAME}.war"
                        ssh -o BatchMode=yes "$TOMCAT_USER@$TOMCAT_HOST" \
                          "sudo /usr/local/sbin/deploy-commerce-war '${SERVICE_NAME}'"
                    '''
                }
            }
        }

        stage('Smoke check') {
            when { expression { return params.DEPLOY } }
            steps {
                sh '''#!/bin/sh
                    set -eu
                    curl --fail --silent --show-error \
                      "http://${TOMCAT_HOST}:8080/${SERVICE_NAME}/health"
                '''
            }
        }
    }
}
```

The sample assumes the target has a carefully reviewed `/usr/local/sbin/deploy-commerce-war` helper that accepts only the three allowlisted service names, verifies the staged file, sets safe ownership and permissions, retains a rollback copy, and performs a controlled deployment. Configure sudoers to allow this one command with fixed arguments as appropriate; do not grant unrestricted sudo. If your pipeline uses a direct `scp` to `webapps`, test ownership, deployment timing, failure handling, and rollback explicitly.

The sample's analysis command illustrates per-service keys, but you must verify SonarQube's project/source scoping for your scanner version. If the analysis includes reactor modules beyond the requested service, change the invocation/configuration before treating the projects as isolated.

The sample publishes after analysis but before optional deployment. If release policy requires deployment only from a release branch or tag, add an explicit branch/tag condition and approval gate. For true decoupled release management, build once, publish an immutable version, and deploy that exact version; do not rebuild a different artifact during deployment.

### 10.3 Pipeline stage intent

| Stage | Purpose | Failure behavior |
| --- | --- | --- |
| Validate selection | Restrict parameter values to known modules | Stop before interpolating untrusted values into commands |
| Checkout | Use the configured SCM revision | No credentials in source or logs |
| Build and test | Build selected service and reactor prerequisites | Fail on compilation/test errors |
| Sonar analysis | Submit the intended service's analysis | Confirm correct key and sources in SonarQube |
| Quality gate | Enforce configured policy | Abort if gate fails or times out |
| Publish | Deploy Maven coordinates to hosted Nexus repositories | Publish only with least-privilege credentials |
| Deploy | Transfer the selected artifact to the runtime | Only when explicitly enabled/authorized |
| Smoke check | Confirm the deployed endpoint responds | Fail deployment verification if unavailable |

## 11. End-to-end setup sequence

1. Create the Git repository and add the parent POM, three child POMs, service bootstrap classes, endpoints, and tests.
2. Run `./mvnw clean verify` locally and confirm all three modules build.
3. Create the SonarQube projects with the three keys. Generate a token and store it in Jenkins Credentials.
4. Create Nexus hosted snapshot and release repositories. Configure Maven publication IDs and Jenkins-managed Maven settings.
5. Install Tomcat and the Java runtime on the target. Create a restricted deployment user and a reviewed deployment helper; configure SSH host-key verification.
6. Configure Jenkins tools, plugins, server connections, credentials, agent labels, and the SCM Pipeline job.
7. Commit the Jenkinsfile and run a build with `DEPLOY=false`. Confirm build, tests, intended SonarQube project, quality gate, and Nexus artifact.
8. Enable deployment for a non-production environment. Select `product-service`, set `DEPLOY=true`, and inspect every stage.
9. Confirm the artifact version and checksum, Tomcat logs, and `/product-service/health` response.
10. Repeat for the other two services, then document a rollback exercise and access-control review.

## 12. Verification checklist

### Build and repository

- [ ] `./mvnw clean verify` succeeds for all modules.
- [ ] A targeted `-pl product-service -am verify` succeeds.
- [ ] The targeted run reports only the expected selected/prerequisite reactor projects.
- [ ] The WAR filename and version are deterministic and match the deployment process.
- [ ] Tests are executed before any skip-tests packaging/deploy command.

### SonarQube and Nexus

- [ ] The selected service updates the expected SonarQube project key.
- [ ] Analysis contains the intended sources and test reports.
- [ ] Quality gate result reaches Jenkins and blocks a failing analysis.
- [ ] Nexus contains the expected group, artifact, version, packaging, and checksum.
- [ ] Credentials are not present in Git, console output, build artifacts, or shell history.

### Deployment

- [ ] Selecting `product-service` does not replace the auth or order WAR.
- [ ] Only the deployment stage has access to the runtime SSH key.
- [ ] SSH checks the host key and the remote account has constrained permissions.
- [ ] The deployed context path and health endpoint respond.
- [ ] Tomcat logs show successful startup without linkage or class-version errors.
- [ ] The previous version can be restored using the documented rollback procedure.

## 13. Troubleshooting

| Symptom | Likely cause and next check |
| --- | --- |
| Maven says no selected project | Run Maven from the repository root and ensure the module path matches `-pl` exactly. |
| Child cannot resolve parent POM | Check parent coordinates and `<relativePath>`; use `-am` when building a dependent module from the reactor. |
| WAR starts locally but fails in Tomcat | Check Java compatibility, Jakarta vs `javax.*`, provided container dependencies, Tomcat logs, and `SpringBootServletInitializer`. |
| Context path differs | Check the deployed WAR filename and Tomcat context configuration; the WAR basename normally determines the path. |
| SonarQube updates the wrong project or includes other modules | Check `sonar.projectKey`, Maven reactor selection, source/test paths, scanner version, and the actual analysis in the UI. |
| Nexus returns 401/403 | Check repository ID matching between `distributionManagement` and Maven settings, credential scope, and deploy privileges. |
| Snapshot/release rejected | Confirm version suffix (`-SNAPSHOT`) matches the target repository policy. |
| SCP succeeds but app is unavailable | Check file ownership, Tomcat appBase, deployment logs, readiness time, network rules, and the smoke-check URL. |
| SSH host verification fails | Provision the correct host key through a trusted channel; do not turn off strict host-key checking. |
| Jenkins and Tomcat cannot both start | They are competing for the same host port; assign distinct ports or split the services across hosts. |

## 14. Production improvements

- Separate CI, artifact storage, analysis, and runtime into independently managed services.
- Use immutable artifact versions, signed provenance, checksums, and artifact promotion between environments.
- Deploy the same built artifact to each environment; avoid rebuilding for production.
- Use TLS, private networking, IAM/instance roles where supported, short-lived credentials, and least privilege.
- Protect Jenkins controller and credentials; run builds on isolated ephemeral agents for untrusted branches.
- Add dependency vulnerability scanning, secret scanning, SBOM generation, and policy gates.
- Use blue/green or rolling deployment with readiness checks and a rehearsed rollback path.
- Add service-level monitoring, logs, tracing, and alerting; configure backups and recovery for Jenkins, Nexus, SonarQube, and application data.
- Reconsider whether a single shared Tomcat is appropriate. Services share a runtime host, failure domain, resource pool, and maintenance window, which weakens service isolation.

## 15. Interview explanation with accurate claims

> “I organized three services in a Maven multi-module repository and used one parameterized Jenkins Pipeline to build a selected module with its reactor prerequisites. The pipeline runs tests and service-specific code analysis, publishes versioned Maven artifacts to Nexus, and can deploy a chosen WAR to Tomcat behind a gated deployment stage. This gives the team one maintained pipeline definition while preserving independent service releases. I would describe the benefit as reducing duplicated pipeline configuration; I would only claim a measured percentage reduction if I had measured it.”

Useful terms: Maven reactor, `-pl` project selection, `-am` also-make, WAR deployment, context path, parameterized pipeline, artifact immutability, quality gate, least privilege, decoupled release, rollback.

## 16. Source documentation

- [Maven multi-module reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
- [Jenkins Pipeline and Jenkinsfile](https://www.jenkins.io/doc/book/pipeline/jenkinsfile/)
- [Jenkins Pipeline syntax](https://www.jenkins.io/doc/book/pipeline/syntax/)
- [Jenkins credentials binding](https://www.jenkins.io/doc/pipeline/steps/credentials-binding/)
- [Jenkins SSH Agent plugin](https://plugins.jenkins.io/ssh-agent/)
- [Apache Tomcat version compatibility](https://tomcat.apache.org/whichversion.html)
