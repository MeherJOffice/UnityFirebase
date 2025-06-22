pipeline {
    agent any

    parameters {
        string(name: 'UNITY_PROJECT_PATH', defaultValue: '/Users/Shared/UnityProjects/MyGame', description: 'Path to Unity Project')
        string(name: 'EMAIL', defaultValue: 'your@email.com', description: 'Contact email for HTML file')
        booleanParam(name: 'BUILD_UNITY', defaultValue: false, description: 'Check to build Unity project')
    }

    environment {
        OUTPUT_DIR = "${env.HOME}/Desktop"
    }

    stages {
        stage('Extract Product Name') {
            steps {
                script {
                    def productName = sh(
                        script: "grep 'productName:' '${params.UNITY_PROJECT_PATH}/ProjectSettings/ProjectSettings.asset' | sed 's/^[^:]*: *//'",
                        returnStdout: true
                    ).trim()
                    env.UNITY_PROJECT_NAME = productName
                    echo "📦 Project Name: ${env.UNITY_PROJECT_NAME}"

                    def baseName = env.UNITY_PROJECT_NAME ?: 'my-ftouh-putaa'

                    // Remove non-alphanumeric and make first letters uppercase for each word
                    def cleanBase = baseName
                    .replaceAll('[^A-Za-z0-9 ]', ' ') // keep letters/numbers/spaces only
                    .split(' ')
                    .collect { it.capitalize() }
                    .join('')
                    .replaceAll(/\s+/, '')+ '-privacy'

                    // Date in ddMM format
                    def datePart = new Date().format('ddMM')

                    def projectId = "${cleanBase}${datePart}"

                    env.UNITY_PROJECT_NAME = projectId

                    echo "🎮 Project ID: ${projectId}"
                }
            }
        }
        stage('Inject BuildHelper.cs') {
                when {
                expression { params.BUILD_UNITY }
                }
            steps {
                script {
                    def jenkinsfiles = "${env.WORKSPACE}/JenkinsFiles"
                    def buildHelperFile = "${jenkinsfiles}/Editor/BuildHelper.cs"
                    def destinationDir = "${params.UNITY_PROJECT_PATH}/Assets/Editor"

                    sh "mkdir -p '${destinationDir}'"
                    sh "cp '${buildHelperFile}' '${destinationDir}/BuildHelper.cs'"

                    echo "✅ BuildHelper.cs injected into project at ${destinationDir}"
                }
            }
        }
        stage('Build Unity Project') {
                when {
                expression { params.BUILD_UNITY }
                }
                    steps {
                        script {
                            def projectPath = params.UNITY_PROJECT_PATH
                            def versionFile = "${projectPath}/ProjectSettings/ProjectVersion.txt"

                            def unityVersion = sh(script: "grep 'm_EditorVersion:' '${versionFile}' | awk '{print \$2}'", returnStdout: true).trim()
                            def unityBinary = "/Applications/Unity/Hub/Editor/${unityVersion}/Unity.app/Contents/MacOS/Unity"

                            echo "Detected Unity version: ${unityVersion}"
                            echo "Starting Unity build using binary: ${unityBinary}"

                            sh """
                '${unityBinary}' -quit -batchmode -projectPath '${projectPath}' -executeMethod BuildHelper.PerformBuild
            """

                            echo '✅ Unity build completed successfully.'
                        }
                    }
        }

        stage('Check Firebase CLI') {
            steps {
                script {
                    // Your custom PATH setup
                    env.PATH = "$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                    echo "🔍 PATH = ${env.PATH}"

                    // Get the full path to the firebase executable
                    def firebaseFullPath = sh(
                script: 'which firebase || true',
                returnStdout: true
            ).trim()

                    if (!firebaseFullPath) {
                        error '❌ Firebase not found!'
                    }

                    // Get the directory containing the firebase executable
                    def firebaseDir = firebaseFullPath.substring(0, firebaseFullPath.lastIndexOf('/'))

                    // Add that directory to PATH
                    env.PATH = "${firebaseDir}:${env.PATH}"

                    echo "✅ Updated PATH: ${env.PATH}"
                    echo "✅ firebasePath: ${firebaseFullPath}"

                    // You can now use 'firebase' safely
                    sh 'firebase --version'
                }
            }
        }

        stage('Init Firebase Project') {
            steps {
                withCredentials([string(credentialsId: 'FIREBASE_CI_TOKEN', variable: 'FIREBASE_TOKEN')]) {
                    script {
                        def projectId = env.UNITY_PROJECT_NAME
                        def projectDir = "${env.HOME}/Desktop/${projectId}"

                        // Find firebase in PATH (with fallback)
                        env.PATH = "$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                        def firebaseFullPath = sh(
                    script: 'which firebase || true',
                    returnStdout: true
                ).trim()
                        if (!firebaseFullPath) {
                            error '❌ Firebase CLI not found in PATH'
                        }
                        def firebaseDir = firebaseFullPath.substring(0, firebaseFullPath.lastIndexOf('/'))

                        // Compose new PATH with firebaseDir prepended
                        def newPath = "${firebaseDir}:${env.PATH}"

                        sh "mkdir -p '${projectDir}'"

                        def projectExists = sh(
                    script: """
                        export PATH="${newPath}"
                        firebase projects:list --token="$FIREBASE_TOKEN" | grep -q "^${projectId}\\b"
                    """,
                    returnStatus: true
                ) == 0

                        if (projectExists) {
                            echo "✅ Firebase project '${projectId}' already exists."
                } else {
                            echo "🚀 Creating Firebase project '${projectId}'..."
                            sh """
                        export PATH="${newPath}"
                        firebase projects:create '${projectId}' --token="$FIREBASE_TOKEN" --non-interactive
                    """
                        }

                        // Write firebase.json
                        writeFile file: "${projectDir}/firebase.json", text: """
{
  "hosting": {
    "public": "public",
    "ignore": [
      "firebase.json",
      "**/.*",
      "**/node_modules/**"
    ]
  }
}
"""

                        // Write .firebaserc
                        writeFile file: "${projectDir}/.firebaserc", text: """
{
  "projects": {
    "default": "${projectId}"
  }
}
"""

                        echo '✅ Firebase config written. Ready to deploy.'
                    }
                }
            }
        }

        stage('Prepare HTML Privacy File') {
            steps {
                script {
                    def projectId = env.UNITY_PROJECT_NAME

                    def outputPath = "${env.HOME}/Desktop/${projectId}/public"

                    sh "mkdir -p '${outputPath}'"

                    def jenkinsfiles = "${env.WORKSPACE}/JenkinsFiles/HTML"
                    def htmlTemplatePath = "${jenkinsfiles}/PrivacyPolicies.html"

                    def htmlContent = readFile(htmlTemplatePath)
                .replace('{Product Name}', env.UNITY_PROJECT_NAME)
                .replace('{email}', params.EMAIL)

                    writeFile file: "${outputPath}/PrivacyPolicies.html", text: htmlContent

                    echo "✅ Generated HTML at: ${outputPath}/PrivacyPolicies.html"
                }
            }
        }

        stage('Deploy to Firebase') {
            steps {
                withCredentials([string(credentialsId: 'FIREBASE_CI_TOKEN', variable: 'FIREBASE_TOKEN')]) {
                    script {
                        def projectId = env.UNITY_PROJECT_NAME
                        def projectDir = "${env.HOME}/Desktop/${projectId}"

                        // Dynamically find the Firebase CLI directory
                        env.PATH = "$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                        def firebaseFullPath = sh(
                    script: 'which firebase || true',
                    returnStdout: true
                ).trim()
                        if (!firebaseFullPath) {
                            error '❌ Firebase CLI not found in PATH'
                        }
                        def firebaseDir = firebaseFullPath.substring(0, firebaseFullPath.lastIndexOf('/'))
                        def newPath = "${firebaseDir}:${env.PATH}"

                        dir(projectDir) {
                            sh """
                        export PATH="${newPath}"
                        firebase deploy --only hosting --token="\$FIREBASE_TOKEN"
                    """
                        }
                    }
                }
            }
        }

        stage('Open Hosted Page') {
            steps {
                script {
                    def projectId = env.UNITY_PROJECT_NAME

                    def hostedUrl = "https://${projectId}.web.app/PrivacyPolicies.html"

                    echo "🌐 Opening hosted URL: ${hostedUrl}"

                    sh "open '${hostedUrl}' || echo '📎 Could not open browser. URL: ${hostedUrl}'"
                }
            }
        }
    }
}
