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
                    env.BUILD_WEB = params.BUILD_ALL || changed('web')
                    env.BUILD_OAUTH2_PROXY_APPLE = params.BUILD_ALL || changed('oauth2-proxy-apple')
                    def workerCommonChanged = changed('worker-common')
                    env.BUILD_SCRAPER = params.BUILD_ALL || changed('scraper-cz') || workerCommonChanged
                    env.BUILD_SCRAPER_EBADATELNA = params.BUILD_ALL || changed('scraper-ebadatelna') || workerCommonChanged
                    env.BUILD_SCRAPER_FINDBUCH = params.BUILD_ALL || changed('scraper-findbuch') || workerCommonChanged
                    env.BUILD_SCRAPER_OESTA = params.BUILD_ALL || changed('scraper-oesta') || workerCommonChanged
                    env.BUILD_SCRAPER_MATRICULA = params.BUILD_ALL || changed('scraper-matricula') || workerCommonChanged
                    env.BUILD_OCR = params.BUILD_ALL || changed('ocr-worker-paddle') || workerCommonChanged
                    env.BUILD_PDF = params.BUILD_ALL || changed('pdf-worker') || workerCommonChanged
                    env.BUILD_TRANSLATE = params.BUILD_ALL || changed('translate-worker') || workerCommonChanged
                    env.BUILD_EMBED = params.BUILD_ALL || changed('embed-worker') || workerCommonChanged
                    echo "backend=${env.BUILD_BACKEND} frontend=${env.BUILD_FRONTEND} web=${env.BUILD_WEB} oauth2-proxy-apple=${env.BUILD_OAUTH2_PROXY_APPLE} scraper-cz=${env.BUILD_SCRAPER} ebadatelna=${env.BUILD_SCRAPER_EBADATELNA} findbuch=${env.BUILD_SCRAPER_FINDBUCH} oesta=${env.BUILD_SCRAPER_OESTA} matricula=${env.BUILD_SCRAPER_MATRICULA} ocr=${env.BUILD_OCR} pdf=${env.BUILD_PDF} translate=${env.BUILD_TRANSLATE} embed=${env.BUILD_EMBED}"
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

                stage('web') {
                    when { expression { env.BUILD_WEB == 'true' } }
                    steps {
                        dir('web') {
                            sh "docker build -t ${prefix}/web:latest -t ${prefix}/web:\${GIT_COMMIT} ."
                        }
                        script {
                            dockerPush(registry, "${prefix}/web:latest")
                            dockerPush(registry, "${prefix}/web:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('oauth2-proxy-apple') {
                    when { expression { env.BUILD_OAUTH2_PROXY_APPLE == 'true' } }
                    steps {
                        sh "docker build -f oauth2-proxy-apple/Dockerfile -t ${prefix}/oauth2-proxy-apple:latest -t ${prefix}/oauth2-proxy-apple:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/oauth2-proxy-apple:latest")
                            dockerPush(registry, "${prefix}/oauth2-proxy-apple:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('scraper-cz') {
                    when { expression { env.BUILD_SCRAPER == 'true' } }
                    steps {
                        sh "docker build -f scraper-cz/Dockerfile -t ${prefix}/scraper-cz:latest -t ${prefix}/scraper-cz:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/scraper-cz:latest")
                            dockerPush(registry, "${prefix}/scraper-cz:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('ocr-worker-paddle') {
                    when { expression { env.BUILD_OCR == 'true' } }
                    steps {
                        sh "docker build -f ocr-worker-paddle/Dockerfile -t ${prefix}/ocr-worker-paddle:latest -t ${prefix}/ocr-worker-paddle:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/ocr-worker-paddle:latest")
                            dockerPush(registry, "${prefix}/ocr-worker-paddle:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('pdf-worker') {
                    when { expression { env.BUILD_PDF == 'true' } }
                    steps {
                        sh "docker build -f pdf-worker/Dockerfile -t ${prefix}/pdf-worker:latest -t ${prefix}/pdf-worker:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/pdf-worker:latest")
                            dockerPush(registry, "${prefix}/pdf-worker:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('translate-worker') {
                    when { expression { env.BUILD_TRANSLATE == 'true' } }
                    steps {
                        sh "docker build -f translate-worker/Dockerfile -t ${prefix}/translate-worker:latest -t ${prefix}/translate-worker:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/translate-worker:latest")
                            dockerPush(registry, "${prefix}/translate-worker:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('embed-worker') {
                    when { expression { env.BUILD_EMBED == 'true' } }
                    steps {
                        sh "docker build -f embed-worker/Dockerfile -t ${prefix}/embed-worker:latest -t ${prefix}/embed-worker:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/embed-worker:latest")
                            dockerPush(registry, "${prefix}/embed-worker:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('scraper-ebadatelna') {
                    when { expression { env.BUILD_SCRAPER_EBADATELNA == 'true' } }
                    steps {
                        sh "docker build -f scraper-ebadatelna/Dockerfile -t ${prefix}/scraper-ebadatelna:latest -t ${prefix}/scraper-ebadatelna:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/scraper-ebadatelna:latest")
                            dockerPush(registry, "${prefix}/scraper-ebadatelna:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('scraper-findbuch') {
                    when { expression { env.BUILD_SCRAPER_FINDBUCH == 'true' } }
                    steps {
                        sh "docker build -f scraper-findbuch/Dockerfile -t ${prefix}/scraper-findbuch:latest -t ${prefix}/scraper-findbuch:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/scraper-findbuch:latest")
                            dockerPush(registry, "${prefix}/scraper-findbuch:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('scraper-oesta') {
                    when { expression { env.BUILD_SCRAPER_OESTA == 'true' } }
                    steps {
                        sh "docker build -f scraper-oesta/Dockerfile -t ${prefix}/scraper-oesta:latest -t ${prefix}/scraper-oesta:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/scraper-oesta:latest")
                            dockerPush(registry, "${prefix}/scraper-oesta:\${GIT_COMMIT}")
                        }
                    }
                }

                stage('scraper-matricula') {
                    when { expression { env.BUILD_SCRAPER_MATRICULA == 'true' } }
                    steps {
                        sh "docker build -f scraper-matricula/Dockerfile -t ${prefix}/scraper-matricula:latest -t ${prefix}/scraper-matricula:\${GIT_COMMIT} ."
                        script {
                            dockerPush(registry, "${prefix}/scraper-matricula:latest")
                            dockerPush(registry, "${prefix}/scraper-matricula:\${GIT_COMMIT}")
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
