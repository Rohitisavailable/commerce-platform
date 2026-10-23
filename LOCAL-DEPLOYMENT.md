# Local deployment guide (Windows)

This guide builds the three services in `commerce-platform` and deploys their WAR files to one local Apache Tomcat instance. It also includes an optional direct-run path for trying one service without Tomcat.

## 1. Install the required tools

Install these on Windows:

1. **JDK 21** (not only a JRE). [Eclipse Temurin 21 downloads](https://adoptium.net/temurin/releases/?version=21&os=windows) provides an MSI installer. Select the JDK for your Windows architecture.
2. **Apache Maven 3.9 or later**. Download the binary ZIP from the [official Maven download page](https://maven.apache.org/download.cgi), extract it (for example, to `C:\tools\apache-maven`), then add its `bin` folder to your user or system `PATH`. Maven's own installation instructions are [here](https://maven.apache.org/install).
3. **Apache Tomcat 10.1**. Download the 64-bit Windows ZIP from the [official Tomcat 10 downloads](https://tomcat.apache.org/download-10). Extract it (for example, to `C:\tools\apache-tomcat-10.1`). Tomcat 10.1 is the compatible Tomcat line for this project's Jakarta Servlet-based Spring Boot WARs. See the [Tomcat version guide](https://tomcat.apache.org/whichversion.html).
4. **Git** is optional if you already have the project folder and do not need to clone or commit it.

Jenkins, SonarQube, Nexus, Docker, and an AWS account are not required for local Tomcat deployment.

## 2. Set up a PowerShell session

Open a new PowerShell window after installing the JDK and Maven. Set `JAVA_HOME` to the actual JDK installation folder on your computer and ensure Maven is on `PATH`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.x.x-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

If you extracted Maven somewhere that is not already on `PATH`, set its folder too:

```powershell
$env:MAVEN_HOME = 'C:\tools\apache-maven'
$env:Path = "$env:MAVEN_HOME\bin;$env:Path"
```

Check that PowerShell can find the right programs:

```powershell
java -version
mvn -version
```

`mvn -version` should report Java 21. If it reports another Java version, correct `JAVA_HOME` and open a fresh PowerShell window.

## 3. Open the project folder

Change to the project directory. Adjust the path if you saved the project elsewhere:

```powershell
Set-Location 'C:\Users\tfroh\Documents\Codex\2026-10-23\files-pasted-by-the-user-this\outputs\commerce-platform'
```

The project root should contain `pom.xml`, `Jenkinsfile`, `README.md`, and the three service folders. This project currently does not include Maven Wrapper scripts, so use the installed `mvn` command.

## 4. Build the WAR files

Build all three modules from the project root:

```powershell
mvn -B clean package
```

To build only one service and any reactor prerequisites it needs, use `-pl` and `-am`. For example:

```powershell
mvn -B -pl product-service -am clean package
```

The WAR files should appear here:

```text
auth-service\target\auth-service.war
product-service\target\product-service.war
order-service\target\order-service.war
```

If Maven fails, resolve the first error it reports before continuing. Common causes are a wrong `JAVA_HOME`, Maven missing from `PATH`, or inability to download dependencies from Maven Central.

## 5. Start Tomcat

Set `CATALINA_HOME` to the directory created when you extracted Tomcat, then start it:

```powershell
$env:CATALINA_HOME = 'C:\tools\apache-tomcat-10.1'
& "$env:CATALINA_HOME\bin\startup.bat"
```

Wait several seconds, then open [http://localhost:8080](http://localhost:8080). If the Tomcat welcome page does not load, check the console output and `$env:CATALINA_HOME\logs` before deploying the applications.

If port 8080 is already occupied, identify the process before changing configuration:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

Change Tomcat's HTTP connector port in `conf\server.xml` only if needed, then use that port in the URLs below. If you installed Tomcat as a Windows service, use that service's configured start and stop controls instead of `startup.bat` and `shutdown.bat`.

## 6. Deploy all services

Copy each WAR to Tomcat's `webapps` directory. Set `CATALINA_HOME` as shown above if this is a new PowerShell session.

```powershell
$webapps = Join-Path $env:CATALINA_HOME 'webapps'
Copy-Item '.\auth-service\target\auth-service.war' $webapps
Copy-Item '.\product-service\target\product-service.war' $webapps
Copy-Item '.\order-service\target\order-service.war' $webapps
```

Tomcat will expand and deploy each WAR. Allow a short time for deployment, then check the Tomcat logs under `logs` for startup errors.

To deploy just one service, build it with the targeted Maven command above and copy only that service's WAR. Its context path is based on the WAR filename, so keep the filenames `auth-service.war`, `product-service.war`, and `order-service.war`.

### Redeploy an updated WAR

For a predictable local redeploy, stop Tomcat, remove only the old WAR and its expanded directory for the service being updated, copy the new WAR, then start Tomcat again. Example for `product-service`:

```powershell
& "$env:CATALINA_HOME\bin\shutdown.bat"
Start-Sleep -Seconds 3
Remove-Item -LiteralPath (Join-Path $env:CATALINA_HOME 'webapps\product-service.war') -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $env:CATALINA_HOME 'webapps\product-service') -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item '.\product-service\target\product-service.war' (Join-Path $env:CATALINA_HOME 'webapps')
& "$env:CATALINA_HOME\bin\startup.bat"
```

These commands assume `CATALINA_HOME` is correct and point only to the local Tomcat `webapps` directory. Do not run the removal commands with a different target path.

## 7. Verify the deployed services

Open these URLs in a browser, or request them from PowerShell:

| Service | Health URL | Sample endpoint |
| --- | --- | --- |
| Auth | `http://localhost:8080/auth-service/api/v1/health` | `http://localhost:8080/auth-service/api/v1/info` |
| Product | `http://localhost:8080/product-service/api/v1/health` | `http://localhost:8080/product-service/api/v1/products` |
| Order | `http://localhost:8080/order-service/api/v1/health` | `http://localhost:8080/order-service/api/v1/orders` |

PowerShell check:

```powershell
Invoke-RestMethod 'http://localhost:8080/product-service/api/v1/health'
Invoke-RestMethod 'http://localhost:8080/product-service/api/v1/products'
```

The health endpoint should return JSON with the service name and status `UP`. The product and order examples use in-memory data; that data resets whenever the service restarts.

## 8. Optional: run one service without Tomcat

For a quick development run, start one service using Spring Boot's Maven plugin:

```powershell
mvn -pl product-service spring-boot:run
```

Then visit `http://localhost:8080/api/v1/health` (there is no `/product-service` prefix when running directly). Stop the process with `Ctrl+C`.

Run multiple services directly only after assigning a different port to each. In PowerShell, for example:

```powershell
$env:SERVER_PORT = '8081'
mvn -pl product-service spring-boot:run
```

Open a separate PowerShell window for another service, set a different `SERVER_PORT` (such as `8082`), and run its Maven command there. `SERVER_PORT` is for direct embedded-server runs; when Tomcat deploys the WARs, Tomcat owns the listening port and each application gets its own context path.

## 9. Stop Tomcat

If Tomcat was started with its scripts:

```powershell
& "$env:CATALINA_HOME\bin\shutdown.bat"
```

Wait for Tomcat to stop before closing the window or replacing deployed files.

## 10. Troubleshooting

| Issue | What to check |
| --- | --- |
| `mvn` is not recognized | Confirm Maven's `bin` directory is on `PATH`, then open a fresh PowerShell session. |
| Maven reports the wrong Java version | Set `JAVA_HOME` to the JDK 21 folder and put `$env:JAVA_HOME\bin` first on `PATH`. |
| Tomcat fails to start | Check port conflicts, Java environment, console output, and Tomcat `logs`. |
| Tomcat page works but service URL is 404 | Confirm the WAR is in `webapps`, its filename matches the context path, and Tomcat finished deploying it. |
| Service returns 500 or fails to deploy | Inspect `$env:CATALINA_HOME\logs\catalina.*.log` for the first exception. Confirm Tomcat 10.1 and Java 21 are being used. |
| Direct run says port already in use | Stop the other service or choose a different `SERVER_PORT`. |
| Old response remains after copying a WAR | Stop Tomcat, remove that service's expanded directory and WAR, copy the new WAR, then restart. |

## 11. What this local setup does not configure

Local Tomcat deployment does not publish artifacts to Nexus, analyze code in SonarQube, run Jenkins, or deploy to AWS. Those integrations require separately installed/configured services and are described in `../enterprise-microservices-delivery-guide.md`. Keep all these sample services bound to localhost or a trusted network while learning; do not expose Tomcat manager or development endpoints publicly.
