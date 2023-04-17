package luceedebug.testutils;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
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
        hostConfig.withPublishAllPorts(true);

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
        

        // hostConfig.withPortBindings(portBindings);

        return new ContainerID(
            dockerClient
                .createContainerCmd(imageID)
                .withExposedPorts(exposedPorts)
                .withHostConfig(hostConfig)
                .exec()
                .getId()
        );
    }

    public static class HostPortBindings {
        public final int http;
        public final int dap;
        HostPortBindings(int http, int dap) {
            this.http = http;
            this.dap = dap;
        }
    }

    /**
     * this is hardcoded to assume we've already bound the ports on the container to particular magic numbers,
     * which should be fixed before we go too much further.
     */
    public static HostPortBindings getPublishedHostPortBindings(DockerClient dockerClient, String containerID) {
        NetworkSettings networkSettings = dockerClient
            .inspectContainerCmd(containerID)
            .exec()
            .getNetworkSettings();

        Ports portBindings = networkSettings.getPorts();
        Map<ExposedPort, Binding[]> bindings = portBindings.getBindings();
    
        int http = -1;
        int dap = -1;
        for (var entry : bindings.entrySet()) {
            int containerPort = entry.getKey().getPort();
            int hostPort = Integer.parseInt(entry.getValue()[0].getHostPortSpec());
            if (containerPort == 8888) {
                http = hostPort;
            }
            else if (containerPort == 10000) {
                dap = hostPort;
            }
        }

        if (http == -1 || dap == -1) {
            throw new RuntimeException("couldn't determine host<->container port bindings");
        }

        return new HostPortBindings(http, dap);
    }
}
