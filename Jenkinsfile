def registry = 'dockerregistry.icomb.place'
def prefix = "${registry}/archiver"

def changed(module) {
    def changes = sh(script: "git diff --name-only HEAD~1 HEAD -- ${module}/", returnStdout: true).trim()
    return changes.length() > 0
}

def dockerPush(image) {
    withCredentials([usernamePassword(credentialsId: 'dockerregistry.icomb.place', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
        sh "echo \$REG_PASS | docker login ${registry} -u \$REG_USER --password-stdin"
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
                    echo "backend=${env.BUILD_BACKEND} frontend=${env.BUILD_FRONTEND} scraper-cz=${env.BUILD_SCRAPER} ocr-worker-paddle=${env.BUILD_OCR}"
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
                            dockerPush("${prefix}/backend:latest")
                            dockerPush("${prefix}/backend:\${GIT_COMMIT}")
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
                            dockerPush("${prefix}/frontend:latest")
                            dockerPush("${prefix}/frontend:\${GIT_COMMIT}")
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
                            dockerPush("${prefix}/scraper-cz:latest")
                            dockerPush("${prefix}/scraper-cz:\${GIT_COMMIT}")
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
                            dockerPush("${prefix}/ocr-worker-paddle:latest")
                            dockerPush("${prefix}/ocr-worker-paddle:\${GIT_COMMIT}")
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh "docker logout ${registry} || true"
        }
    }
}
