pipeline {
    agent any

    parameters {
        string(name: 'UNITY_PROJECT_PATH', defaultValue: '/Users/Shared/UnityProjects/MyGame', description: 'Path to Unity Project')
        string(name: 'EMAIL', defaultValue: 'your@email.com', description: 'Contact email for HTML file')
        string(name: 'GAME_NAME', defaultValue: 'GAME NAME', description: 'The game name on the store')
        booleanParam(name: 'BUILD_UNITY', defaultValue: true, description: 'Build Unity project in this run?')
    }

    environment {
        OUTPUT_DIR = "${env.HOME}/Desktop"
    }

    stages {
        stage('Extract Product Name') {
            steps {
                script {
                    def productName = params.GAME_NAME?.trim()
                    if (!productName) {
                        productName = sh(
                    script: "grep 'productName:' '${params.UNITY_PROJECT_PATH}/ProjectSettings/ProjectSettings.asset' | sed 's/^[^:]*: *//'",
                    returnStdout: true
                ).trim()
                    }
                    env.UNITY_PROJECT_NAME = productName
                    echo "📦 Project Name: ${env.UNITY_PROJECT_NAME}"
                }
            }
        }

        stage('Discover Firebase CLI') {
            steps {
                script {
                    // Update PATH for discovery (standard locations)
                    env.PATH = "$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                    def firebaseFullPath = sh(
                        script: 'which firebase || true',
                        returnStdout: true
                    ).trim()
                    if (!firebaseFullPath) {
                        error '❌ Firebase CLI not found in PATH'
                    }
                    def firebaseDir = firebaseFullPath.substring(0, firebaseFullPath.lastIndexOf('/'))
                    env.DYNAMIC_PATH = "${firebaseDir}:${env.PATH}"
                    env.FIREBASE_BIN = firebaseFullPath
                    echo "✅ Firebase CLI: ${env.FIREBASE_BIN}"
                    echo "✅ PATH will be: ${env.DYNAMIC_PATH}"
                    sh """
                        export PATH="${env.DYNAMIC_PATH}"
                        firebase --version
                    """
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

        stage('Set Firebase Project ID') {
            steps {
                script {
                    def getFirebaseProjectId = { rawName ->
                        def cleanBase = (rawName ?: 'my-Game2265')
                            .toLowerCase()
                            .replaceAll('[^a-z0-9]', '-')
                            .replaceAll('-+', '-')
                            .replaceAll('(^-|-$)', '')
                        def today = new Date().format('ddMM')
                        return "${cleanBase}${today}-privacy"
                    }
                    env.FIREBASE_PROJECT_ID = getFirebaseProjectId(env.UNITY_PROJECT_NAME)
                    echo "🎮 Firebase Project ID: ${env.FIREBASE_PROJECT_ID}"
                }
            }
        }

        stage('Init Firebase Project') {
            steps {
                withCredentials([string(credentialsId: 'FIREBASE_CI_TOKEN', variable: 'FIREBASE_TOKEN')]) {
                    script {
                        def projectId = env.FIREBASE_PROJECT_ID
                        def projectDir = "${env.HOME}/Desktop/${projectId}"

                        sh "mkdir -p '${projectDir}'"

                        def projectExists = sh(
                            script: """
                                export PATH="${env.DYNAMIC_PATH}"
                                firebase projects:list --token="\$FIREBASE_TOKEN" | grep -q "^${projectId}\\b"
                            """,
                            returnStatus: true
                        ) == 0

                        if (projectExists) {
                            echo "✅ Firebase project '${projectId}' already exists."
                        } else {
                            echo "🚀 Creating Firebase project '${projectId}'..."
                            sh """
                                export PATH="${env.DYNAMIC_PATH}"
                                firebase projects:create '${projectId}' --token="\$FIREBASE_TOKEN" --non-interactive
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
                    def projectId = env.FIREBASE_PROJECT_ID
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
                        def projectId = env.FIREBASE_PROJECT_ID
                        def projectDir = "${env.HOME}/Desktop/${projectId}"

                        dir(projectDir) {
                            sh """
                                export PATH="${env.DYNAMIC_PATH}"
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
                    def projectId = env.FIREBASE_PROJECT_ID
                    def hostedUrl = "https://${projectId}.web.app/PrivacyPolicies.html"

                    echo "🌐 Opening hosted URL: ${hostedUrl}"

                    // On macOS, use open; on Linux, you might use xdg-open or skip this
                    sh "open '${hostedUrl}' || echo '📎 Could not open browser. URL: ${hostedUrl}'"
                }
            }
        }
    }
}
