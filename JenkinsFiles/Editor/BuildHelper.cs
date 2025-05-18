using UnityEditor;
using UnityEngine;
using System.IO;
using System.Linq;
using UnityEditor.Build.Reporting;

public class BuildHelper
{
    public static void PerformBuild()
    {
        string projectName = PlayerSettings.productName;
        string buildFolder = Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.Personal), "jenkinsBuildFireBase", projectName, "UnityBuild");

        if (!Directory.Exists(buildFolder))
            Directory.CreateDirectory(buildFolder);

        Debug.Log("Building iOS project: " + projectName + " into: " + buildFolder);

        // ✅ Use the actual Build Settings scenes (not hard-coded)
        string[] scenes = EditorBuildSettings.scenes
            .Where(scene => scene.enabled)
            .Select(scene => scene.path)
            .ToArray();

        if (scenes.Length == 0)
        {
            Debug.LogError("❌ No scenes enabled in Build Settings!");
            return;
        }

        // ✅ For iOS: Provide folder path for Xcode project generation (NOT .app)
        BuildPlayerOptions buildOptions = new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = buildFolder, // Folder where Xcode project will be generated
            target = BuildTarget.iOS,
            options = BuildOptions.None
        };

        BuildReport report = BuildPipeline.BuildPlayer(buildOptions);
        BuildSummary summary = report.summary;

        if (summary.result == BuildResult.Succeeded)
        {
            Debug.Log("✅ Build succeeded: " + summary.totalSize + " bytes");
        }
        else if (summary.result == BuildResult.Failed)
        {
            Debug.LogError("❌ Build failed!");
        }
    }
}
