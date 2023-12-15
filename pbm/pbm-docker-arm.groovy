library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker-64gb-aarch64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'PBM_REPO_CH', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PBM_VERSION', defaultValue: '2.3.0-1', description: 'PBM version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','AWS_ECR','DockerHub'], description: 'Target repo for docker image, use DockerHub for release only')
        choice(name: 'LATEST', choices: ['no','yes'], description: 'Tag image as latest')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PBM_REPO_CH}-${params.PBM_VERSION}"
                }
            }
        }
        stage ('Build image') {
            steps {
                sh """
                    MAJ_VER=\$(echo ${params.PBM_VERSION} | awk -F "." '{print \$1}')
                    echo \$MAJ_VER
                    MIN_VER=\$(echo ${params.PBM_VERSION} | awk -F "-" '{print \$1}')
                    echo \$MIN_VER
                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-backup-mongodb
                    sed -E "s/ENV PBM_VERSION (.+)/ENV PBM_VERSION ${params.PBM_VERSION}/" -i Dockerfile.aarch64
                    sed -E "s/ENV PBM_REPO_CH (.+)/ENV PBM_REPO_CH ${params.PBM_REPO_CH}/" -i Dockerfile.aarch64
                    docker build . -t percona-backup-mongodb -f Dockerfile.aarch64
                    """
            }
        }
        stage ('Run trivy analyzer') {
            steps {
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-ARM64.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-ARM64.tar.gz -C /usr/local/bin/
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
                    if [ ${params.PBM_REPO_CH} = "release" ]; then
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-backup-mongodb
                    else
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-backup-mongodb
                    fi
               """
            }
            post {
                always {
                    junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
        stage ('Push image to aws ecr') {
            when {
                environment name: 'TARGET_REPO', value: 'AWS_ECR'
            }
            steps {
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                     sh """
                         curl "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o "awscliv2.zip"
                         if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && apt-get -y install unzip ; fi
                         unzip -o awscliv2.zip
                         sudo ./aws/install
                         aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                         docker tag percona-backup-mongodb public.ecr.aws/e7j3v3n0/psmdb-build:pbm-${params.PBM_VERSION}-arm64
                         docker push public.ecr.aws/e7j3v3n0/psmdb-build:pbm-${params.PBM_VERSION}-arm64
                     """
                }
            }
        }
        stage ('Push images to perconalab') {
            when {
                environment name: 'TARGET_REPO', value: 'PerconaLab'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=\$(echo ${params.PBM_VERSION} | awk -F "." '{print \$1}')
                         MIN_VER=\$(echo ${params.PBM_VERSION} | awk -F "-" '{print \$1}')
                         docker tag percona-backup-mongodb perconalab/percona-backup-mongodb:\$MAJ_VER-arm64
                         docker push perconalab/percona-backup-mongodb:\$MAJ_VER-arm64
                         docker tag percona-backup-mongodb perconalab/percona-backup-mongodb:\$MIN_VER-arm64
                         docker push perconalab/percona-backup-mongodb:\$MIN_VER-arm64

                         docker manifest create perconalab/percona-backup-mongodb:\$MIN_VER-multi \
                            perconalab/percona-backup-mongodb:\$MIN_VER \
                            perconalab/percona-backup-mongodb:\$MIN_VER-arm64
                         docker manifest annotate perconalab/percona-backup-mongodb:\$MIN_VER-multi \
                            perconalab/percona-backup-mongodb:\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-backup-mongodb:\$MIN_VER-multi \
                            perconalab/percona-backup-mongodb:\$MIN_VER --os linux --arch amd64
                         docker manifest inspect perconalab/percona-backup-mongodb:\$MIN_VER-multi
                         docker manifest push perconalab/percona-backup-mongodb:\$MIN_VER-multi

                         docker manifest create perconalab/percona-backup-mongodb:\$MAJ_VER-multi \
                            perconalab/percona-backup-mongodb:\$MAJ_VER \
                            perconalab/percona-backup-mongodb:\$MAJ_VER-arm64
                         docker manifest annotate perconalab/percona-backup-mongodb:\$MAJ_VER-multi \
                            perconalab/percona-backup-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-backup-mongodb:\$MAJ_VER-multi \
                            perconalab/percona-backup-mongodb:\$MAJ_VER --os linux --arch amd64
                         docker manifest inspect perconalab/percona-backup-mongodb:\$MAJ_VER-multi
                         docker manifest push perconalab/percona-backup-mongodb:\$MAJ_VER-multi

                         if [ ${params.LATEST} = "yes" ]; then
                           docker manifest create perconalab/percona-backup-mongodb:latest \
                              perconalab/percona-backup-mongodb:\$MAJ_VER \
                              perconalab/percona-backup-mongodb:\$MAJ_VER-arm64
                           docker manifest annotate perconalab/percona-backup-mongodb:latest \
                              perconalab/percona-backup-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                           docker manifest annotate perconalab/percona-backup-mongodb:latest \
                              perconalab/percona-backup-mongodb:\$MAJ_VER --os linux --arch amd64
                           docker manifest inspect perconalab/percona-backup-mongodb:latest
                           docker manifest push perconalab/percona-backup-mongodb:latest
			 fi
                     """
                }
            }
        }
        stage ('Push images to official percona docker registry') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=\$(echo ${params.PBM_VERSION} | awk -F "." '{print \$1}')
                         MIN_VER=\$(echo ${params.PBM_VERSION} | awk -F "-" '{print \$1}')
                         docker tag percona-backup-mongodb percona/percona-backup-mongodb:\$MAJ_VER-arm64
                         docker push percona/percona-backup-mongodb:\$MAJ_VER-arm64
                         docker tag percona-backup-mongodb percona/percona-backup-mongodb:\$MIN_VER-arm64
                         docker push percona/percona-backup-mongodb:\$MIN_VER-arm64

                         docker manifest create percona/percona-backup-mongodb:\$MIN_VER-multi \
                            percona/percona-backup-mongodb:\$MIN_VER \
                            percona/percona-backup-mongodb:\$MIN_VER-arm64
                         docker manifest annotate percona/percona-backup-mongodb:\$MIN_VER-multi \
                            percona/percona-backup-mongodb:\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-backup-mongodb:\$MIN_VER-multi \
                            percona/percona-backup-mongodb:\$MIN_VER --os linux --arch amd64
                         docker manifest inspect percona/percona-backup-mongodb:\$MIN_VER-multi
                         docker manifest push percona/percona-backup-mongodb:\$MIN_VER-multi

                         docker manifest create percona/percona-backup-mongodb:\$MAJ_VER-multi \
                            percona/percona-backup-mongodb:\$MAJ_VER \
                            percona/percona-backup-mongodb:\$MAJ_VER-arm64
                         docker manifest annotate percona/percona-backup-mongodb:\$MAJ_VER-multi \
                            percona/percona-backup-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-backup-mongodb:\$MAJ_VER-multi \
                            percona/percona-backup-mongodb:\$MAJ_VER --os linux --arch amd64
                         docker manifest inspect percona/percona-backup-mongodb:\$MAJ_VER-multi
                         docker manifest push percona/percona-backup-mongodb:\$MAJ_VER-multi

                         if [ ${params.LATEST} = "yes" ]; then
                           docker manifest create percona/percona-backup-mongodb:latest \
                              percona/percona-backup-mongodb:\$MAJ_VER \
                              percona/percona-backup-mongodb:\$MAJ_VER-arm64
                           docker manifest annotate percona/percona-backup-mongodb:latest \
                              percona/percona-backup-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                           docker manifest annotate percona/percona-backup-mongodb:latest \
                              percona/percona-backup-mongodb:\$MAJ_VER --os linux --arch amd64
                           docker manifest inspect percona/percona-backup-mongodb:latest
                           docker manifest push percona/percona-backup-mongodb:latest
                         fi
                     """
                }
            }
        }
    }
    post {
        always {
            sh """
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            """
            deleteDir()
        }
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Building of PBM ${PBM_VERSION} for ARM, repo ${PBM_REPO_CH} succeed")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: Building of PBM ${PBM_VERSION} for ARM, repo ${PBM_REPO_CH} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: Building of PBM ${PBM_VERSION} for ARM, repo ${PBM_REPO_CH} failed - [${BUILD_URL}]")
        }
    }
}
