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
               description: 'Service to build, publish, and optionally deploy')
        booleanParam(name: 'DEPLOY', defaultValue: false,
                     description: 'Deploy the selected WAR to the configured Tomcat host')
    }

    environment {
        SONAR_SERVER = 'SonarQube-Server'
        TOMCAT_HOST = 'tomcat.example.internal'
        TOMCAT_USER = 'commerce-deploy'
        TOMCAT_DEPLOY_HELPER = '/usr/local/sbin/deploy-commerce-war'
    }

    stages {
        stage('Validate selection') {
            steps {
                script {
                    def sonarKeys = [
                        'auth-service': 'commerce-auth',
                        'product-service': 'commerce-prod',
                        'order-service': 'commerce-order'
                    ]
                    if (!sonarKeys.containsKey(params.SERVICE_NAME)) {
                        error('Unsupported service selection')
                    }
                    env.SELECTED_SONAR_KEY = sonarKeys[params.SERVICE_NAME]
                }
            }
        }

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build and verify') {
            steps {
                sh './mvnw -B -pl "${SERVICE_NAME}" -am clean verify'
                archiveArtifacts artifacts: "${SERVICE_NAME}/target/${SERVICE_NAME}.war",
                                 fingerprint: true
            }
        }

        stage('SonarQube analysis') {
            steps {
                withSonarQubeEnv(env.SONAR_SERVER) {
                    sh '''./mvnw -B -f "${SERVICE_NAME}/pom.xml" \\
                      org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \\
                      -Dsonar.projectKey="${SELECTED_SONAR_KEY}"'''
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
                          "sudo $TOMCAT_DEPLOY_HELPER '${SERVICE_NAME}'"
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
                      "http://${TOMCAT_HOST}:8080/${SERVICE_NAME}/api/v1/health"
                '''
            }
        }
    }
}
