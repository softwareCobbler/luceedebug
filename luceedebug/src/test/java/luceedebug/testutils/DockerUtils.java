package luceedebug.testutils;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;

import javax.annotation.Nonnull;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.ExposedPorts;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.Ports.Binding;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

public class DockerUtils {
    public static DockerClient getDefaultDockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * TODO: should be a record but we'd need to setup tooling for tests to be for java14,
     * while the main lib stays on 11.
     */
    public static class ImageID {
        private final String imageID_;
        ImageID(String imageID) {
            this.imageID_ = imageID;
        }
        public String getImageID() { return imageID_; }
    }

    /**
     * Docker won't actually build a new image if the dockerfile hasn't changed, is that right?
     * That would be the desireable behavior.
     */
    public static ImageID buildOrGetImage(DockerClient dockerClient, File dockerFile) {
        return new ImageID(
            dockerClient
                .buildImageCmd(dockerFile)
                .start()
                .awaitImageId()
        );
    }

    public static class ContainerID {
        private final String containerID_;
        ContainerID(String containerID) {
            this.containerID_ = containerID;
        }
        public String getContainerID() { return containerID_; }
    }

    public static ContainerID getFreshDefaultContainer(
        DockerClient dockerClient,
        String imageID, 
        File projectRoot,
        File luceeTestAppRoot,
        int[][] portMappingPairs
    ) {
        var portBindings = new ArrayList<PortBinding>();
        var exposedPorts = new ArrayList<ExposedPort>();

        for (var pair : portMappingPairs) {
            var host = pair[0];
            var container = pair[0];
            portBindings.add(new PortBinding(new Binding(null, String.valueOf(host)), new ExposedPort(container)));
            exposedPorts.add(new ExposedPort(container));
        }

        var hostConfig = new HostConfig();

        hostConfig.setBinds(
            new Bind(
                Paths.get(projectRoot.toString(), "build/libs/").toString(),
                new Volume("/build/")
            ),
            new Bind(
                luceeTestAppRoot.toString(),
                new Volume("/var/www/")
            )
        );

        hostConfig.withPortBindings(portBindings);

        return new ContainerID(
            dockerClient
                .createContainerCmd(imageID)
                .withHostConfig(hostConfig)
                .withExposedPorts(exposedPorts)
                .exec()
                .getId()
        );
    }
}
