def call() {
        sh """
            sudo yum repolist
            python3 --version
            sudo yum module install -y python39
            sudo yum install -y gcc python39-pip python39-devel libselinux-python3
            sudo yum remove ansible -y
            python3 --version
            python3.9 --version
            python3.9 -m venv virtenv
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install --upgrade pip
            python3 -m pip install --upgrade setuptools
            python3 -m pip install --upgrade setuptools-rust
            python3 -m pip install --upgrade molecule==3.3.0 pytest-testinfra pytest molecule-ec2==0.3 molecule[ansible] boto3 boto
        """
}
