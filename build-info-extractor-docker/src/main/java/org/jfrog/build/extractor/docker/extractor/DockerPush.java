package org.jfrog.build.extractor.docker.extractor;

import com.google.common.collect.ArrayListMultimap;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.Module;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryBuildInfoClientBuilder;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryDependenciesClientBuilder;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.extractor.docker.DockerJavaWrapper;
import org.jfrog.build.extractor.docker.DockerUtils;
import org.jfrog.build.extractor.docker.types.DockerImage;
import org.jfrog.build.extractor.docker.types.DockerLayer;
import org.jfrog.build.extractor.docker.types.DockerLayers;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.jfrog.build.extractor.clientConfiguration.util.DeploymentUrlUtils.buildMatrixParamsString;
import static org.jfrog.build.extractor.docker.DockerUtils.initTempDir;
import static org.jfrog.build.extractor.packageManager.PackageManagerUtils.createArtifactoryClientConfiguration;

public class DockerPush extends DockerCommand {
    private final ArrayListMultimap<String, String> artifactProperties;


    /**
     * @param buildInfoClientBuilder    - Build Info client builder.
     * @param dependenciesClientBuilder - Dependencies client builder.
     * @param targetRepository          - The repository it'll deploy to.
     * @param imageTag                  - Image tag to push.
     * @param logger                    - The logger.
     * @param username                  - Artifactory user name
     * @param password                  - Artifactory password
     * @param host                      - Docker daemon ip.
     * @param artifactProperties        - Properties to be attached to the docker layers deployed to Artifactory.
     * @param env                       - Environment variables to use during docker push execution.
     */
    public DockerPush(ArtifactoryBuildInfoClientBuilder buildInfoClientBuilder, ArtifactoryDependenciesClientBuilder dependenciesClientBuilder,
                      String imageTag, String host, ArrayListMultimap<String, String> artifactProperties, String targetRepository, String username,
                      String password, Log logger, Map<String, String> env) {
        super(buildInfoClientBuilder, dependenciesClientBuilder, imageTag, host, targetRepository, username, password, logger, env);
        this.artifactProperties = artifactProperties;
    }

    /**
     * Allow running docker push using a new Java process.
     *
     * @param ignored ignores input incoming params.
     */
    public static void main(String[] ignored) {
        try {
            ArtifactoryClientConfiguration clientConfiguration = createArtifactoryClientConfiguration();
            // Client builders.
            ArtifactoryBuildInfoClientBuilder buildInfoClientBuilder = new ArtifactoryBuildInfoClientBuilder().setClientConfiguration(clientConfiguration, clientConfiguration.publisher);
            ArtifactoryDependenciesClientBuilder dependenciesClientBuilder = new ArtifactoryDependenciesClientBuilder().setClientConfiguration(clientConfiguration, clientConfiguration.publisher);
            // Load artifact and BuildInfo properties from publisher section in the BuildInfo.properties file.
            ArtifactoryClientConfiguration.DockerHandler dockerHandler = clientConfiguration.dockerHandler;
            // Init DockerPush.
            DockerPush dockerPush = new DockerPush(buildInfoClientBuilder,
                    dependenciesClientBuilder,
                    dockerHandler.getImageTag(),
                    dockerHandler.getHost(),
                    ArrayListMultimap.create(clientConfiguration.publisher.getMatrixParams().asMultimap()),
                    clientConfiguration.publisher.getRepoKey(),
                    clientConfiguration.publisher.getUsername(),
                    clientConfiguration.publisher.getPassword(),
                    clientConfiguration.getLog(),
                    clientConfiguration.getAllProperties());
            initTempDir(new File(clientConfiguration.info.getGeneratedBuildInfoFilePath()));
            // Exe docker push & collect build info.
            dockerPush.executeAndSaveBuildInfo(clientConfiguration);
        } catch (RuntimeException e) {
            ExceptionUtils.printRootCauseStackTrace(e, System.out);
            System.exit(1);
        }
    }

    @Override
    public Build execute() {
        String message = "Pushing image: " + imageTag;
        if (StringUtils.isNotEmpty(host)) {
            message += " using docker daemon host: " + host;
        }
        logger.info(message);
        try {
            DockerJavaWrapper.pushImage(imageTag, username, password, host, env, logger);
            String imageId = DockerJavaWrapper.getImageIdFromTag(imageTag, host, env, logger);
            DockerImage image = new DockerImage(imageId, imageTag, targetRepository, buildInfoClientBuilder, dependenciesClientBuilder, "", "");
            Module module = image.generateBuildInfoModule(logger, DockerUtils.CommandType.Push);
            if (module.getArtifacts() == null || module.getArtifacts().size() == 0) {
                logger.warn("Could not find docker image: " + imageTag + " in Artifactory.");
            }
            setImageLayersProps(image.getLayers(), artifactProperties, buildInfoClientBuilder);
            Build build = new Build();
            modulesList.add(module);
            build.setModules(modulesList);
            logger.info("Successfully pushed docker image: " + imageTag);
            return build;
        } catch (IOException | InterruptedException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Update each layer's properties with artifactProperties.
     */
    private void setImageLayersProps(DockerLayers layers, ArrayListMultimap<String, String> artifactProperties, ArtifactoryBuildInfoClientBuilder buildInfoClientBuilder) throws IOException {
        String artifactsPropsStr = buildMatrixParamsString(artifactProperties, false);
        try (ArtifactoryBuildInfoClient buildInfoClient = buildInfoClientBuilder.build()) {
            for (DockerLayer layer : layers.getLayers()) {
                HttpResponse httpResponse = null;
                try {
                    httpResponse = buildInfoClient.executeUpdateFileProperty(layer.getFullPath(), artifactsPropsStr);
                    validateSetImageLayersResponse(httpResponse);
                } finally {
                    if (httpResponse != null) {
                        EntityUtils.consume(httpResponse.getEntity());
                    }
                }
            }
        }
    }

    private void validateSetImageLayersResponse(HttpResponse httpResponse) throws IOException {
        int code = httpResponse.getStatusLine().getStatusCode();
        if (code != 204) {
            String response = DockerUtils.entityToString(httpResponse.getEntity());
            throw new IOException("Failed while trying to set properties on docker layer: " + response);
        }
    }
}