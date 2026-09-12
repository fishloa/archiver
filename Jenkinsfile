def registry = 'dockerregistry.icomb.place'
def prefix = "${registry}/archiver"

def changed(module) {
    def changes = sh(script: "git diff --name-only HEAD~1 HEAD -- ${module}/", returnStdout: true).trim()
    return changes.length() > 0
}

/**
 * The release version, or empty when this is not a release build.
 *
 * A release is a build of a commit that carries an exact tag. Asking git directly means this
 * works on a plain pipeline job, which has no TAG_NAME — that is a multibranch-only variable.
 */
def releaseVersion() {
    return sh(script: "git describe --exact-match --tags HEAD 2>/dev/null || true",
              returnStdout: true).trim()
}

/**
 * Tags for one service.
 *
 * A main build publishes :<commit> and moves :test, which is what the test stack runs. Only a
 * release moves :latest, so ":latest" means "the latest release" and production never picks up
 * an untagged commit merely because a build went green.
 */
def tagsFor(prefix, service, isRelease, version, commit) {
    def tags = ["${prefix}/${service}:${commit}"]
    if (isRelease) {
        tags << "${prefix}/${service}:${version}"
        tags << "${prefix}/${service}:latest"
    } else {
        tags << "${prefix}/${service}:test"
    }
    return tags
}

def buildAndPush(reg, prefix, service, context, isRelease, version, commit, extraArgs = '') {
    def tags = tagsFor(prefix, service, isRelease, version, commit)
    def tagArgs = tags.collect { "-t ${it}" }.join(' ')
    def versionArgs = "--build-arg APP_VERSION=${isRelease ? version : 'dev-' + commit.take(7)}" +
                      " --build-arg APP_COMMIT=${commit}"
    sh "docker build ${tagArgs} ${versionArgs} ${extraArgs} ${context}"
    tags.each { dockerPush(reg, it) }
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
                    env.RELEASE_VERSION = releaseVersion()
                    env.IS_RELEASE = (env.RELEASE_VERSION ? 'true' : 'false')

                    // A release must be coherent: production runs every image at the same
                    // version, so a tag build rebuilds everything rather than whatever the last
                    // commit happened to touch.
                    def buildAll = params.BUILD_ALL || env.IS_RELEASE == 'true'
                    echo(env.IS_RELEASE == 'true'
                         ? "Release build: ${env.RELEASE_VERSION} -> production"
                         : "Main build -> test stack")

                    env.BUILD_BACKEND = buildAll || changed('backend')
                    env.BUILD_FRONTEND = buildAll || changed('frontend')
                    env.BUILD_WEB = buildAll || changed('web')
                    env.BUILD_OAUTH2_PROXY_APPLE = buildAll || changed('oauth2-proxy-apple')
                    def workerCommonChanged = changed('worker-common')
                    env.BUILD_SCRAPER = buildAll || changed('scraper-cz') || workerCommonChanged
                    env.BUILD_SCRAPER_EBADATELNA = buildAll || changed('scraper-ebadatelna') || workerCommonChanged
                    env.BUILD_SCRAPER_FINDBUCH = buildAll || changed('scraper-findbuch') || workerCommonChanged
                    env.BUILD_SCRAPER_OESTA = buildAll || changed('scraper-oesta') || workerCommonChanged
                    env.BUILD_SCRAPER_MATRICULA = buildAll || changed('scraper-matricula') || workerCommonChanged
                    env.BUILD_SCRAPER_AROLSEN = buildAll || changed('scraper-arolsen') || workerCommonChanged
                    env.BUILD_SCRAPER_DDB = buildAll || changed('scraper-ddb') || workerCommonChanged
                    env.BUILD_SCRAPER_BARCH = buildAll || changed('scraper-barch') || workerCommonChanged
                    echo "backend=${env.BUILD_BACKEND} frontend=${env.BUILD_FRONTEND} web=${env.BUILD_WEB} oauth2-proxy-apple=${env.BUILD_OAUTH2_PROXY_APPLE} scraper-cz=${env.BUILD_SCRAPER} ebadatelna=${env.BUILD_SCRAPER_EBADATELNA} findbuch=${env.BUILD_SCRAPER_FINDBUCH} oesta=${env.BUILD_SCRAPER_OESTA} matricula=${env.BUILD_SCRAPER_MATRICULA} arolsen=${env.BUILD_SCRAPER_AROLSEN} ddb=${env.BUILD_SCRAPER_DDB} barch=${env.BUILD_SCRAPER_BARCH}"
                }
            }
        }

        stage('Test') {
            parallel {
                stage('test-backend') {
                    when { expression { env.BUILD_BACKEND == 'true' } }
                    steps {
                        dir('backend') {
                            sh '''
                                tar cf - . | docker run --rm -i \
                                    --network=host \
                                    -v /var/run/docker.sock:/var/run/docker.sock \
                                    -v archiver-gradle-cache:/root/.gradle \
                                    eclipse-temurin:25-jdk \
                                    sh -c "mkdir -p /app && cd /app && tar xf - && chmod +x gradlew && ./gradlew test --no-daemon"
                            '''
                        }
                    }
                }
                stage('test-frontend') {
                    when { expression { env.BUILD_FRONTEND == 'true' } }
                    steps {
                        dir('frontend') {
                            sh '''
                                tar cf - . | docker run --rm -i \
                                    oven/bun:1-alpine \
                                    sh -c "mkdir -p /app && cd /app && tar xf - && bun install --frozen-lockfile && bun run check"
                            '''
                        }
                    }
                }
                stage('test-scraper-cz') {
                    when { expression { env.BUILD_SCRAPER == 'true' } }
                    steps {
                        sh '''
                            tar cf - worker-common scraper-cz | docker run --rm -i \
                                python:3.14-slim \
                                sh -c "mkdir -p /repo && cd /repo && tar xf - && pip install -e worker-common && pip install -e 'scraper-cz[test]' && pytest scraper-cz/tests -v"
                        '''
                    }
                }
                stage('test-scraper-barch') {
                    when { expression { env.BUILD_SCRAPER_BARCH == 'true' } }
                    steps {
                        sh '''
                            tar cf - worker-common scraper-barch | docker run --rm -i \
                                python:3.14-slim \
                                sh -c "mkdir -p /repo && cd /repo && tar xf - && pip install -e worker-common && pip install -e 'scraper-barch[test]' && pytest scraper-barch/tests -v"
                        '''
                    }
                }
            }
        }

        stage('Build & Push') {
            parallel {
                stage('backend') {
                    when { expression { env.BUILD_BACKEND == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'backend', 'backend',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT)
                        }
                    }
                }

                stage('frontend') {
                    when { expression { env.BUILD_FRONTEND == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'frontend', 'frontend',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT)
                        }
                    }
                }

                stage('web') {
                    when { expression { env.BUILD_WEB == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'web', 'web',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT)
                        }
                    }
                }

                stage('oauth2-proxy-apple') {
                    when { expression { env.BUILD_OAUTH2_PROXY_APPLE == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'oauth2-proxy-apple', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f oauth2-proxy-apple/Dockerfile')
                        }
                    }
                }

                stage('scraper-cz') {
                    when { expression { env.BUILD_SCRAPER == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-cz', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-cz/Dockerfile')
                        }
                    }
                }

                stage('scraper-ebadatelna') {
                    when { expression { env.BUILD_SCRAPER_EBADATELNA == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-ebadatelna', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-ebadatelna/Dockerfile')
                        }
                    }
                }

                stage('scraper-findbuch') {
                    when { expression { env.BUILD_SCRAPER_FINDBUCH == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-findbuch', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-findbuch/Dockerfile')
                        }
                    }
                }

                stage('scraper-oesta') {
                    when { expression { env.BUILD_SCRAPER_OESTA == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-oesta', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-oesta/Dockerfile')
                        }
                    }
                }

                stage('scraper-matricula') {
                    when { expression { env.BUILD_SCRAPER_MATRICULA == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-matricula', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-matricula/Dockerfile')
                        }
                    }
                }

                stage('scraper-arolsen') {
                    when { expression { env.BUILD_SCRAPER_AROLSEN == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-arolsen', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-arolsen/Dockerfile')
                        }
                    }
                }

                stage('scraper-ddb') {
                    when { expression { env.BUILD_SCRAPER_DDB == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-ddb', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-ddb/Dockerfile')
                        }
                    }
                }

                stage('scraper-barch') {
                    when { expression { env.BUILD_SCRAPER_BARCH == 'true' } }
                    steps {
                        script {
                            buildAndPush(registry, prefix, 'scraper-barch', '.',
                                         env.IS_RELEASE == 'true', env.RELEASE_VERSION,
                                         env.GIT_COMMIT, '-f scraper-barch/Dockerfile')
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                // A green build on main deploys the test stack. Production moves only for a
                // tagged release, so shipping is a deliberate act rather than a side effect of
                // pushing to main.
                def hook = env.IS_RELEASE == 'true'
                    ? 'b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71'   // production, stack 183
                    : 'c4d81f60-2a37-4b19-9e05-7f3ac6821de4'   // test, stack 212
                def target = env.IS_RELEASE == 'true' ? "PRODUCTION (${env.RELEASE_VERSION})" : 'test'
                echo "Deploying to ${target}"
                // -f so a rejected webhook fails the build instead of passing silently, which
                // is how a deploy could look green while production stayed on the old image.
                sh "curl -sf -X POST https://docker.icomb.place/api/stacks/webhooks/${hook}"
            }
        }
        always {
            sh "docker logout ${registry} || true"
        }
    }
}
