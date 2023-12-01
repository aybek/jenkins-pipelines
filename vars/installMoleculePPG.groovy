def call() {
        sh """
            sudo yum install -y gcc python3-pip python3-devel libselinux-python3
            sudo yum remove ansible -y
            python3 -m venv virtenv
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install --upgrade pip
            python3 -m pip install --upgrade setuptools
            python3 -m pip install --upgrade setuptools-rust
            python3 -m pip install --upgrade molecule pytest-testinfra pytest molecule-plugins[ec2] molecule[ansible] ansible-lint boto3 boto
            ansible-galaxy collection install amazon.aws:==3.3.1 --force
            ansible-galaxy collection install community.aws
            ansible-galaxy collection install ansible.posix
            echo $PATH 
            pip list
            ansible --version && molecule --version
            ansible-galaxy collection list
        """
}
