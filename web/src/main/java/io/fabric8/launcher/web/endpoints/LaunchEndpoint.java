package io.fabric8.launcher.web.endpoints;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.ws.rs.BeanParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.fabric8.launcher.base.Paths;
import io.fabric8.launcher.core.api.DefaultMissionControl;
import io.fabric8.launcher.core.api.ImmutableAsyncBoom;
import io.fabric8.launcher.core.api.events.LauncherStatusEventKind;
import io.fabric8.launcher.core.api.events.StatusMessageEvent;
import io.fabric8.launcher.core.api.events.StatusMessageEventBroker;
import io.fabric8.launcher.core.api.projectiles.CreateProjectile;
import io.fabric8.launcher.core.api.projectiles.ImmutableLauncherCreateProjectile;
import io.fabric8.launcher.core.api.security.Secured;
import io.fabric8.launcher.core.spi.DirectoryReaper;
import io.fabric8.launcher.web.endpoints.inputs.LaunchProjectileInput;
import io.fabric8.launcher.web.endpoints.inputs.UploadZipProjectileInput;
import io.fabric8.launcher.web.endpoints.inputs.ZipProjectileInput;
import org.apache.commons.lang3.time.StopWatch;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;

import static java.util.Arrays.asList;

/**
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
@Path("/launcher")
@RequestScoped
public class LaunchEndpoint {

    private static final String APPLICATION_ZIP = "application/zip";

    private static Logger log = Logger.getLogger(LaunchEndpoint.class.getName());

    @Inject
    private DefaultMissionControl missionControl;

    @Inject
    private StatusMessageEventBroker eventBroker;

    @Inject
    private DirectoryReaper reaper;

    @GET
    @Path("/zip")
    @Produces(APPLICATION_ZIP)
    public Response zip(@Valid @BeanParam ZipProjectileInput zipProjectile) throws IOException {
        CreateProjectile projectile = null;
        try {
            projectile = missionControl.prepare(zipProjectile);
            String filename = Objects.toString(zipProjectile.getProjectName(), zipProjectile.getArtifactId());
            byte[] zipContents = Paths.zip(filename, projectile.getProjectLocation());
            return Response
                    .ok(zipContents)
                    .type(APPLICATION_ZIP)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".zip\"")
                    .build();
        } finally {
            if (projectile != null) {
                reaper.delete(projectile.getProjectLocation());
            }
        }
    }

    @POST
    @Path("/launch")
    @Secured
    @Produces(MediaType.APPLICATION_JSON)
    public void launch(@Valid @BeanParam LaunchProjectileInput launchProjectileInput, @Suspended AsyncResponse asyncResponse,
                       @Context HttpServletResponse response) throws IOException {
        CreateProjectile projectile = ImmutableLauncherCreateProjectile.builder()
                .from(missionControl.prepare(launchProjectileInput))
                .startOfStep(launchProjectileInput.getExecutionStep())
                .eventConsumer(eventBroker::send)
                .build();
        doLaunch(projectile, response, asyncResponse);
    }

    @POST
    @Path("/upload")
    @Secured
    @Produces(MediaType.APPLICATION_JSON)
    public void uploadZip(@Valid @MultipartForm UploadZipProjectileInput input, @Suspended AsyncResponse asyncResponse,
                          @Context HttpServletResponse response) throws IOException {
        java.nio.file.Path projectDir = Files.createTempDirectory("projectDir");
        Paths.unzip(input.getZipContents(), projectDir);
        java.nio.file.Path projectLocation;
        try (DirectoryStream<java.nio.file.Path> stream =
                     Files.newDirectoryStream(projectDir)) {
            projectLocation = stream.iterator().next();
        }
        CreateProjectile projectile = ImmutableLauncherCreateProjectile.builder()
                .projectLocation(projectLocation)
                .eventConsumer(eventBroker::send)
                .gitOrganization(input.getGitOrganization())
                .gitRepositoryName(input.getGitRepository())
                .startOfStep(input.getExecutionStep())
                .openShiftProjectName(input.getProjectName())
                .build();
        try {
            doLaunch(projectile, response, asyncResponse);
        } finally {
            reaper.delete(projectDir);
        }
    }


    private void doLaunch(CreateProjectile projectile, @Context HttpServletResponse response, @Suspended AsyncResponse asyncResponse) throws IOException {
        // No need to hold off the processing, return the status link immediately
        // Need to close the response's OutputStream after resuming to automatically flush the contents
        try (ServletOutputStream stream = response.getOutputStream()) {
            asyncResponse.resume(ImmutableAsyncBoom.builder()
                                         .uuid(projectile.getId())
                                         .eventTypes(asList(LauncherStatusEventKind.values()))
                                         .build());
        }

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            log.log(Level.INFO, "Launching projectile {0}", projectile);
            missionControl.launch(projectile);
            stopWatch.stop();
            log.log(Level.INFO, "Projectile {0} launched. Time Elapsed: {1}", new Object[]{projectile.getId(), stopWatch});
        } catch (Exception ex) {
            stopWatch.stop();
            log.log(Level.WARNING, "Projectile " + projectile + " failed to launch. Time Elapsed: " + stopWatch, ex);
            projectile.getEventConsumer().accept(new StatusMessageEvent(projectile.getId(), ex));
        } finally {
            reaper.delete(projectile.getProjectLocation());
        }
    }

}