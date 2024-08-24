package luceedebug.testutils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestParams {
    public static class DockerInfo {
        public final Path luceedebugProjectRoot = Paths.get("").toAbsolutePath();
        public final File dockerFile;
        
        DockerInfo(String projectRelativeDockerRoot) {
            Path v = luceedebugProjectRoot.resolve(projectRelativeDockerRoot).normalize();
            this.dockerFile = v.resolve("Dockerfile").toFile();
        }

        public File getTestWebRoot(String webRoot) {
            File f = luceedebugProjectRoot.resolve("../test/docker/" + webRoot).normalize().toFile();
            assert f.exists() : "No such file: '" + f + "'";
            return f;
        }
    }

    public static DockerInfo[] getDockerFilePaths() {
        return new DockerInfo[] {
            new DockerInfo("../test/docker/5.3.10.120")
        };
    }
}
