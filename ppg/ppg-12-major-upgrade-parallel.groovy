library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/ppg/pg-12-major-upgrade"

pipeline {
  agent {
      label 'micro-amazon'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
        choice(
            name: 'FROM_REPO',
            description: 'From this repo will be upgraded PPG',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'FROM_VERSION',
            description: 'From this version PPG will be updated',
            choices: [
                'ppg-11.5',
                'ppg-11.6',
                'ppg-11.7',
                'ppg-11.8'
            ]
        )
        choice(
            name: 'VERSION',
            description: 'To this version PPG will be updated',
            choices: [
                'ppg-12.2',
                'ppg-12.3'
            ]
        )
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: 'master', url: 'https://github.com/Percona-QA/package-testing.git'
            }
        }
        stage ('Prepare') {
          steps {
                script {
                   installMolecule()
             }
           }
        }
        stage('Test') {
          steps {
                script {
                    moleculeParallelTest(ppgOperatingSystems(), moleculeDir)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(ppgOperatingSystems(), moleculeDir)
         }
      }
   }
}
