library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installDependencies() {
    sh '''
        export PATH=${PATH}:~/.local/bin
        sudo yum install -y git python3-pip
        python3 -m venv venv
        source venv/bin/activate
        python3 -m pip install --upgrade pip setuptools wheel
        python3 -m pip install molecule==2.22 ansible boto boto3 paramiko testinfra
    '''

    sh '''
        rm -rf package-testing
        git clone https://github.com/Percona-QA/package-testing
        cd package-testing
        git checkout wip-test-ps-innodb-cluster
    '''
}

void setInstanceEnvironment(String instanceName) {
    def instances = readYaml(
        file: "${env.WORKSPACE}/package-testing/molecule/configuration.yml"
    )

    def instance = instances[instanceName]
    if (instance == null) {
        error("instance `${instanceName}` is not defined in configuration YAML")
    }

    env.TEST_IMAGE = instance.image
    env.TEST_ROOT_DEVICE_NAME = instance.root_device_name
    env.TEST_SSH_USER = instance.user
}

void runMoleculeAction(String action) {
    def awsCredentials = [
        sshUserPrivateKey(
            credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
            keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
            passphraseVariable: '',
            usernameVariable: ''
        ),
        aws(
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]

    withCredentials(awsCredentials) {
        sh """
            source venv/bin/activate
            export MOLECULE_DEBUG=1
            cd package-testing/molecule/ps-innodb-cluster-server
            molecule ${action}
            cd ../ps-innodb-cluster-router
            molecule ${action}
        """
    }
}

pipeline {
    agent {
        label 'micro-amazon'
    }

    options {
        skipDefaultCheckout()
    }

    parameters {
        string(
            name: 'UPSTREAM_VERSION',
            defaultValue: '8.0.23',
            description: 'Upstream MySQL version'
        )
        string(
            name: 'PS_VERSION',
            defaultValue: '14',
            description: 'Percona part of version'
        )
        string(
            name: 'PS_REVISION',
            defaultValue: '3558242',
            description: 'Short git hash for release'
        )
        choice(
            name: 'TEST_DIST',
            choices: [
                'ubuntu-focal',
                'ubuntu-bionic',
                'ubuntu-xenial',
                'debian-10',
                'debian-9',
                'centos-8',
                'centos-7',
                'centos-6'
            ],
            description: 'Distribution to run test'
        )
        choice(
            name: 'INSTALL_REPO',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )
    }

    stages {
        stage("Set up") {
            steps {
                installDependencies()
                setInstanceEnvironment(params.TEST_DIST)
            }
        }

        stage("Create") {
            steps {
                runMoleculeAction("create")
            }
        }

        stage("Converge") {
            steps {
                runMoleculeAction("converge")
            }
        }

        stage("Verify") {
            steps {
                runMoleculeAction("verify")
            }
        }
    }

    post {
        always {
            script {
                runMoleculeAction("destroy")
            }
        }
    }
}