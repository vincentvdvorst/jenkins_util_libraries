class SemVer {
  def major, minor, patch

  SemVer() {
    this.major = 0
    this.minor = 0
    this.patch = 0
  }

  SemVer(semverstr) {
    try {
      this.major = semverstr.split("\\.")[0].toInteger()
      this.minor = semverstr.split("\\.")[1].toInteger()
      this.patch = semverstr.split("\\.")[2].toInteger()
    }
    
    catch(err) {
      throw new Exception("This constructor expects a sane Semantic Version string: \"major.minor.patch\" e.g. \"2.1.1\"")
    }
  }

  SemVer(major, minor, patch) {
    try {
      this.major = major
      this.minor = minor
      this.patch = patch
    }
    catch(err) {
      throw new Exception("This constructor expects 3 integer values for major, minor and patch versions")
    }
  }

  def set(major, minor, patch) {
    try {
      this.major = major
      this.minor = minor
      this.patch = patch
    }
    catch(err) {
      throw new Exception("This method expects 3 integer values for major, minor and patch versions")
    }
  }

  def set(semverstr) {
    try {
      this.major = semverstr.split("\\.")[0].toInteger()
      this.minor = semverstr.split("\\.")[1].toInteger()
      this.patch = semverstr.split("\\.")[2].toInteger()
    }
    
    catch(err) {
      throw new Exception("This method expects a sane Semantic Version string: \"major.minor.patch\" e.g. \"2.1.1\"")
    }
  }

  def isNewerThan(other) {
    if ((this.major > other.major) || (this.major == other.major && this.minor > other.minor) || (this.major == other.major && this.minor == other.minor && this.patch > other.patch)) {
      return true
    }
    return false
  }

  String toString() {
    return "${this.major.toString()}.${this.minor.toString()}.${this.patch.toString()}"
  }
}

def fetch(scm, cookbookDirectory, currentBranch) {
  checkout([$class: 'GitSCM',
    branches: scm.branches,
    doGenerateSubmoduleConfigurations: scm.doGenerateSubmoduleConfigurations,
    extensions: scm.extensions + [
      [$class: 'RelativeTargetDirectory',relativeTargetDir: cookbookDirectory],
      [$class: 'CleanBeforeCheckout'],
      [$class: 'WipeWorkspace'],
      [$class: 'LocalBranch', localBranch: currentBranch]
    ],
    userRemoteConfigs: scm.userRemoteConfigs
  ])
}

String getNewVersion(scm, cookbookDirectory, currentBranch) {
  fetch(scm, cookbookDirectory, currentBranch)
  newVersion = new SemVer()
  dir(cookbookDirectory) {
    def metadataLines = readFile "metadata.rb"

    for (line in metadataLines.split("\n")) {
      if (line ==~ /^version.*/) {
        newVersion.set(line.split(" ")[1].replace("\'", ""))
      }
    }
  }
  return newVersion.toString()
}

def versionCheck(scm, cookbookDirectory, currentBranch, cookbook) {
  echo "Checking if version is updated."
  try {
    fetch(scm, cookbookDirectory, currentBranch)
    dir(cookbookDirectory) {
      newVersion = new SemVer()

      def metadataLines = readFile "metadata.rb"

      for (line in metadataLines.split("\n")) {
        if (line ==~ /^version.*/) {
          newVersion.set(line.split(" ")[1].replace("\'", ""))
        }
      }

      cookbookDetails = ""

      currentVersion = new SemVer()

      try {
        cookbookDetails = bat(returnStdout: true, script: """
          @echo off
          knife cookbook show ${cookbook}
        """)
        echo cookbookDetails
        currentVersion.set(cookbookDetails.split()[1])
      }
      catch(err) {
        echo "Cookbook is not present on Chef server, no version bump is required."
      }

      if (!newVersion.isNewerThan(currentVersion)) {
        throw new Exception("The version that has been set is not newer than the previous version.")
      } else {
        echo "The version has been set appropriately. Existing version: ${currentVersion.toString()}, new version is: ${newVersion.toString()}"
      }
      currentBuild.result = 'SUCCESS'
    }
  }
  catch(err) {
    currentBuild.result = 'FAILED'
    echo err.getMessage()
    error err.getMessage()
    throw err
  }
}

def lintTest(scm, cookbookDirectory, currentBranch, cookbook) {
  echo "cookbook: ${cookbook}"
  echo "current branch: ${currentBranch}"
  echo "checkout directory: ${cookbookDirectory}"
  try {
    fetch(scm, cookbookDirectory, currentBranch)
    dir(cookbookDirectory) {
      bat "chef exec cookstyle ."
    }
    currentBuild.result = 'SUCCESS'
  }
  catch(err) {
    currentBuild.result = 'FAILED'
    throw err
  }
}

def unitTests(scm, cookbookDirectory, currentBranch, cookbook) {
  try {
    fetch(scm, cookbookDirectory, currentBranch)
    dir(cookbookDirectory) {
      bat "berks install"
      bat "chef exec rspec ."
    }
    currentBuild.result = 'SUCCESS'
  }
  catch(err) {
    currentBuild.result = 'FAILED'
    throw err
  }
}

def functionalTests(scm, cookbookDirectory, currentBranch, cookbook) {
  try {
    fetch(scm, cookbookDirectory, currentBranch)    
    dir(cookbookDirectory) {
      bat '''
        set KITCHEN_YAML=.kitchen.jenkins.yml
        kitchen verify
      '''
    }
    currentBuild.result = 'SUCCESS'
  }
  catch(err) {
    currentBuild.result = 'FAILED'
    throw err
  }
  finally {
    dir(cookbookDirectory) {
      bat '''
        set KITCHEN_YAML=.kitchen.jenkins.yml
        kitchen destroy
      '''
    }
  }
}

def publish(scm, cookbookDirectory, currentBranch, stableBranch, cookbook) {
  if ( currentBranch == stableBranch ) {
    echo "Attempting upload of stable branch cookbook to Chef server."
    try{
      fetch(scm, cookbookDirectory, currentBranch)
      dir(cookbookDirectory) {
        bat "berks vendor"
        bat "berks upload --halt-on-frozen"
        currentBuild.result = 'SUCCESS'
      }
    }
    catch(err){
      currentBuild.result = 'FAILED'
      throw err
    }
  } else {
    echo "Skipping Publishing stage as this is not the stable branch."
  }
}

def updateGitTag(scm, cookbookDirectory, currentBranch) {
  version = getNewVersion(scm, cookbookDirectory, currentBranch)
  dir(cookbookDirectory) {
    credentialId = "16fab210-1259-4cb5-9acc-c2134ac32ea4"
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialId, usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD']]) {
      echo "New version is: ${version}"
      gitURL = powershell(script: "git remote get-url origin", returnStdout: true).trim().split("//")[1]
      encodedPassword = java.net.URLEncoder.encode(GIT_PASSWORD, "UTF-8")
      powershell(script: "git config user.name \"Jenkins Builder\"")
      powershell(script: "git config user.email \"cog@gamestop.com\"")
      powershell(script: "git tag -a ${version} -m ${version}")
      powershell(script: "git push https://'${GIT_USERNAME}':'${encodedPassword}'@${gitURL} ${version}")
    }
  }
}