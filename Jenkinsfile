def registry = 'dockerregistry.icomb.place'
def prefix = "${registry}/archiver"

def changed(module) {
    def changes = sh(script: "git diff --name-only HEAD~1 HEAD -- ${module}/", returnStdout: true).trim()
    return changes.length() > 0
}

def dockerPush(reg, image) {
    withCredentials([usernamePassword(credentialsId: 'dockerregistry.icomb.place', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
        sh "echo \$REG_PASS | docker login ${reg} -u \$REG_USER --password-stdin"
    }
    sh "docker push ${image}"
}

pipeline {
    agent any

    parameters {
        booleanParam(name: 'BUILD_ALL', defaultValue: false, description: 'Build all services regardless of changes')
    }

    stages {
        stage('Detect Changes') {
            steps {
                script {
                    env.BUILD_BACKEND = params.BUILD_ALL || changed('backend')
                    env.BUILD_FRONTEND = params.BUILD_ALL || changed('frontend')
                    env.BUILD_SCRAPER = params.BUILD_ALL || changed('scraper-cz')
                    env.BUILD_OCR = params.BUILD_ALL || changed('ocr-worker-paddle')
                    env.BUILD_PDF = params.BUILD_ALL || changed('pdf-worker')
                    env.BUILD_TRANSLATE = params.BUILD_ALL || changed('translate-worker')
                    echo "backend=${env.BUILD_BACKEND} frontend=${env.BUILD_FRONTEND} scraper-cz=${env.BUILD_SCRAPER} ocr=${env.BUILD_OCR} pdf=${env.BUILD_PDF} translate=${env.BUILD_TRANSLATE}"
                }
            }
        }

        stage('Build & Push') {
            parallel {
                stage('backend') {
                    when { expression { env.BUILD_BACKEND == 'true' } }
                    steps {
                        dir('backend') {
                            sh "docker build -t ${prefix}/backend:latest -t ${prefix}/backend:\${GIT_COMMIT} ."
                        }
                        script {
                            dockerPush(registry, "${prefix}/backend:latest")
                            dockerPush(registry, "${prefix}/backend:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('frontend') {
                    when { expression { env.BUILD_FRONTEND == 'true' } }
                    steps {
                        dir('frontend') {
                            sh "docker build -t ${prefix}/frontend:latest -t ${prefix}/frontend:\${GIT_COMMIT} ."
                        }
                        script {
                            dockerPush(registry, "${prefix}/frontend:latest")
                            dockerPush(registry, "${prefix}/frontend:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('scraper-cz') {
                    when { expression { env.BUILD_SCRAPER == 'true' } }
                    steps {
                        dir('scraper-cz') {
                            sh "docker build -t ${prefix}/scraper-cz:latest -t ${prefix}/scraper-cz:\${GIT_COMMIT} ."
                        }
                        script {
                            dockerPush(registry, "${prefix}/scraper-cz:latest")
                            dockerPush(registry, "${prefix}/scraper-cz:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('ocr-worker-paddle') {
                    when { expression { env.BUILD_OCR == 'true' } }
                    steps {
                        dir('ocr-worker-paddle') {
                            sh "docker build -t ${prefix}/ocr-worker-paddle:latest -t ${prefix}/ocr-worker-paddle:\${GIT_COMMIT} ."
                        }
                        script {
                            dockerPush(registry, "${prefix}/ocr-worker-paddle:latest")
                            dockerPush(registry, "${prefix}/ocr-worker-paddle:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('pdf-worker') {
                    when { expression { env.BUILD_PDF == 'true' } }
                    steps {
                        dir('pdf-worker') {
                            sh "docker build -t ${prefix}/pdf-worker:latest -t ${prefix}/pdf-worker:\${GIT_COMMIT} ."
                        }
                        script {
                            dockerPush(registry, "${prefix}/pdf-worker:latest")
                            dockerPush(registry, "${prefix}/pdf-worker:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('translate-worker') {
                    when { expression { env.BUILD_TRANSLATE == 'true' } }
                    steps {
                        dir('translate-worker') {
                            sh "docker build -t ${prefix}/translate-worker:latest -t ${prefix}/translate-worker:\${GIT_COMMIT} ."
                        }
                        script {
                            dockerPush(registry, "${prefix}/translate-worker:latest")
                            dockerPush(registry, "${prefix}/translate-worker:\${GIT_COMMIT}")
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            sh 'curl -s -X POST https://docker.icomb.place/api/stacks/webhooks/b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71 || true'
        }
        always {
            sh "docker logout ${registry} || true"
        }
    }
}
