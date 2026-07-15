node('linux') {
  stage ('Poll') {
    checkout([
      $class: 'GitSCM', branches: [[name: '*/main']], extensions: [],
      userRemoteConfigs: [[url: 'https://github.com/zopencommunity/fdport.git']]])
  }
  stage('Build') {
    build job: 'Port-Pipeline', parameters: [
      string(name: 'PORT_GITHUB_REPO', value: 'https://github.com/zopencommunity/fdport.git'),
      string(name: 'PORT_DESCRIPTION', value: 'A simple, fast and user-friendly alternative to find'),
      string(name: 'BUILD_LINE', value: 'STABLE')
    ]
  }
}
