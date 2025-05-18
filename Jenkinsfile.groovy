pipeline {
    agent any

    parameters {
        string(name: 'UNITY_PROJECT_PATH', defaultValue: '/Users/Shared/UnityProjects/MyGame', description: 'Path to Unity Project')
        string(name: 'EMAIL', defaultValue: 'your@email.com', description: 'Contact email for HTML file')
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
                }
            }
        }
        stage('Inject BuildHelper.cs') {
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
                        expression { params.GAME_ENGINE == 'unity' }
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
                sh '''
            export PATH="$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
            echo "🔍 PATH = $PATH"
            which firebase || echo "❌ Firebase not found"
            firebase --version || echo "❌ Failed to get firebase version"
        '''
            }
        }

        stage('Init Firebase Project') {
            steps {
                withCredentials([string(credentialsId: 'FIREBASE_CI_TOKEN', variable: 'FIREBASE_TOKEN')]) {
                    script {
                        def projectId = "${env.UNITY_PROJECT_NAME}Privacy"
                        def projectDir = "${OUTPUT_DIR}/${projectId}"
                        def firebasePath = "/Users/meher/.npm-global/bin:$PATH"

                        // Ensure the directory exists
                        sh "mkdir -p '${projectDir}'"

                        // Init hosting
                        dir(projectDir) {
                            sh """
                        export PATH="${firebasePath}"
                        firebase init hosting \\
                            --project='${projectId}' \\
                            --force --non-interactive \\
                            --token="$FIREBASE_TOKEN"
                    """
                        }
                    }
                }
            }
        }

        stage('Prepare HTML Privacy File') {
            steps {
                script {
                    def jenkinsfiles = "${env.WORKSPACE}/JenkinsFiles/HTML"
                    def htmlTemplatePath = "${jenkinsfiles}/PrivacyPolicies.html"
                    def outputPath = "${env.HOME}/Desktop/${env.UNITY_PROJECT_NAME}Privacy/public"

                    sh "mkdir -p '${outputPath}'"

                    def htmlContent = readFile(htmlTemplatePath)
                .replace('{Product Name}', env.UNITY_PROJECT_NAME)
                .replace('{email}', params.EMAIL)

                    writeFile file: "${outputPath}/index.html", text: htmlContent

                    echo "✅ Generated HTML file at: ${outputPath}/index.html"
                }
            }
        }
        stage('Deploy to Firebase') {
            steps {
                withCredentials([string(credentialsId: 'FIREBASE_CI_TOKEN', variable: 'FIREBASE_TOKEN')]) {
                    dir("${OUTPUT_DIR}/${env.UNITY_PROJECT_NAME}Privacy") {
                        sh '''
                    export PATH="/Users/meher/.npm-global/bin:$PATH"
                    firebase deploy --only hosting --token="$FIREBASE_TOKEN"
                '''
                    }
                }
            }
        }

        stage('Open Hosted Page') {
            steps {
                script {
                    def url = "https://${env.UNITY_PROJECT_NAME}Privacy.web.app"
                    echo "🌐 Opening ${url}"
                    sh "open '${url}'"
                }
            }
        }
    }
}
