// Declarative pipeline: build once, promote the SAME immutable image dev -> prod.
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        REGISTRY   = 'registry.example.com'
        IMAGE_NAME = "${REGISTRY}/payments/payment-event-relay"
        MAVEN_OPTS = '-Dmaven.repo.local=.m2/repository'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    // Immutable tag = commit SHA: the exact code in prod is always traceable.
                    env.IMAGE_TAG = sh(returnStdout: true, script: 'git rev-parse --short=12 HEAD').trim()
                }
            }
        }

        stage('Build & unit/integration tests') {
            steps {
                sh './mvnw -B clean verify'   // compiles, runs all tests, produces JaCoCo coverage
            }
            post {
                always {
                    junit 'target/surefire-reports/*.xml'
                    archiveArtifacts artifacts: 'target/site/jacoco/**', allowEmptyArchive: true
                }
            }
        }

        stage('Static analysis') {
            steps {
                withSonarQubeEnv('sonarqube') {
                    sh './mvnw -B sonar:sonar -Dsonar.projectKey=payment-event-relay'
                }
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Dependency scan') {
            steps {
                // Fails the build on known high/critical CVEs in third-party libraries.
                sh './mvnw -B org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7'
            }
            post {
                always {
                    archiveArtifacts artifacts: 'target/dependency-check-report.html', allowEmptyArchive: true
                }
            }
        }

        stage('Build image') {
            steps {
                sh 'docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
            }
        }

        stage('Image scan') {
            steps {
                sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed ${IMAGE_NAME}:${IMAGE_TAG}'
            }
        }

        stage('Push image') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(credentialsId: 'registry-credentials',
                        usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                    sh '''
                        echo "$REG_PASS" | docker login ${REGISTRY} -u "$REG_USER" --password-stdin
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                    '''
                }
            }
        }

        stage('Deploy to dev') {
            when { branch 'main' }
            steps {
                withKubeConfig([credentialsId: 'kubeconfig-nonprod']) {
                    sh '''
                        cd k8s/overlays/dev
                        kustomize edit set image ${IMAGE_NAME}=${IMAGE_NAME}:${IMAGE_TAG}
                        kubectl apply -k .
                        kubectl -n payments-dev rollout status deployment/payment-event-relay --timeout=300s
                    '''
                }
            }
        }

        stage('Smoke test dev') {
            when { branch 'main' }
            steps {
                withKubeConfig([credentialsId: 'kubeconfig-nonprod']) {
                    sh '''
                        kubectl -n payments-dev run smoke-${BUILD_NUMBER} --rm -i --restart=Never \
                          --image=curlimages/curl -- \
                          curl -fsS http://payment-event-relay/actuator/health/readiness
                    '''
                }
            }
        }

        stage('Approve production') {
            when { branch 'main' }
            steps {
                timeout(time: 2, unit: 'DAYS') {
                    input message: "Promote ${IMAGE_TAG} to production?", ok: 'Deploy', submitter: 'payments-release-approvers'
                }
            }
        }

        stage('Deploy to prod') {
            when { branch 'main' }
            steps {
                withKubeConfig([credentialsId: 'kubeconfig-prod']) {
                    sh '''
                        cd k8s/overlays/prod
                        kustomize edit set image ${IMAGE_NAME}=${IMAGE_NAME}:${IMAGE_TAG}
                        kubectl apply -k .
                        kubectl -n payments-prod rollout status deployment/payment-event-relay --timeout=600s \
                          || (kubectl -n payments-prod rollout undo deployment/payment-event-relay && exit 1)
                    '''
                }
            }
        }
    }

    post {
        failure {
            echo "Build failed - notify the team channel here (Slack/Teams plugin)."
        }
        always {
            sh 'docker image rm ${IMAGE_NAME}:${IMAGE_TAG} || true'
        }
    }
}
