package io.fabric8.launcher.service.git.gitlab;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.launcher.base.http.HttpClient;
import io.fabric8.launcher.base.identity.TokenIdentity;
import io.fabric8.launcher.service.git.AbstractGitService;
import io.fabric8.launcher.service.git.api.GitHook;
import io.fabric8.launcher.service.git.api.GitOrganization;
import io.fabric8.launcher.service.git.api.GitRepository;
import io.fabric8.launcher.service.git.api.GitRepositoryFilter;
import io.fabric8.launcher.service.git.api.GitService;
import io.fabric8.launcher.service.git.api.GitUser;
import io.fabric8.launcher.service.git.api.ImmutableGitHook;
import io.fabric8.launcher.service.git.api.ImmutableGitOrganization;
import io.fabric8.launcher.service.git.api.ImmutableGitRepository;
import io.fabric8.launcher.service.git.api.ImmutableGitUser;
import io.fabric8.launcher.service.git.api.NoSuchOrganizationException;
import io.fabric8.launcher.service.git.api.NoSuchRepositoryException;
import io.fabric8.launcher.service.git.gitlab.api.GitLabEnvVarSysPropNames;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import static io.fabric8.launcher.base.http.AuthorizationType.PRIVATE_TOKEN;
import static io.fabric8.launcher.base.http.Requests.securedRequest;
import static io.fabric8.launcher.base.http.Requests.urlEncode;
import static io.fabric8.launcher.service.git.Gits.checkGitRepositoryFullNameArgument;
import static io.fabric8.launcher.service.git.Gits.checkGitRepositoryNameArgument;
import static io.fabric8.launcher.service.git.Gits.createGitRepositoryFullName;
import static io.fabric8.launcher.service.git.Gits.isValidGitRepositoryFullName;
import static io.fabric8.launcher.service.git.gitlab.api.GitLabWebhookEvent.ISSUES;
import static io.fabric8.launcher.service.git.gitlab.api.GitLabWebhookEvent.MERGE_REQUESTS;
import static io.fabric8.launcher.service.git.gitlab.api.GitLabWebhookEvent.PUSH;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

/**
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
class GitLabService extends AbstractGitService implements GitService {

    private static final MediaType APPLICATION_FORM_URLENCODED = MediaType.parse("application/x-www-form-urlencoded");

    private static final String GITLAB_URL = GitLabEnvVarSysPropNames.LAUNCHER_MISSIONCONTROL_GITLAB_URL.value("https://gitlab.com");

    private final TokenIdentity identity;

    private final HttpClient httpClient;

    GitLabService(final TokenIdentity identity, HttpClient httpClient) {
        super(identity);
        this.identity = identity;
        this.httpClient = httpClient;
    }

    @Override
    public TokenIdentity getIdentity() {
        return identity;
    }

    @Override
    protected CredentialsProvider getJGitCredentialsProvider() {
        return new UsernamePasswordCredentialsProvider("", identity.getToken());
    }

    @Override
    public List<GitOrganization> getOrganizations() {
        Request request = request()
                .get()
                .url(GITLAB_URL + "/api/v4/groups")
                .build();
        return httpClient.executeAndParseJson(request, (JsonNode tree) -> {
            return StreamSupport.stream(tree.spliterator(), false)
                    .map(node -> ImmutableGitOrganization.of(node.get("path").asText()))
                    .sorted()
                    .collect(Collectors.<GitOrganization>toList());
        }).orElse(Collections.emptyList());
    }


    @Override
    public List<GitRepository> getRepositories(final GitRepositoryFilter filter) {
        requireNonNull(filter, "filter must be specified.");

        final StringBuilder urlBuilder = new StringBuilder(GITLAB_URL)
                .append("/api/v4");
        if (filter.withOrganization() != null) {
            final String organizationName = filter.withOrganization().getName();
            checkOrganizationExistsAndReturnId(organizationName);
            urlBuilder.append("/groups/").append(organizationName);
        } else {
            urlBuilder.append("/users/").append(getLoggedUser().getLogin());
        }
        urlBuilder.append("/projects");
        if (isNotEmpty(filter.withNameContaining())) {
            urlBuilder.append("?search=").append(urlEncode(filter.withNameContaining()));
        }
        Request request = request()
                .get()
                .url(urlBuilder.toString())
                .build();
        return httpClient.executeAndParseJson(request, (JsonNode tree) -> {
            List<GitRepository> orgs = new ArrayList<>();
            for (JsonNode node : tree) {
                orgs.add(readGitRepository(node));
            }
            return orgs;
        }).orElse(Collections.emptyList());
    }

    @Override
    public GitRepository createRepository(GitOrganization organization, String repositoryName, String description) throws IllegalArgumentException {
        // Precondition checks
        checkGitRepositoryNameArgument(repositoryName);
        requireNonNull(description, "description must be specified.");
        if (description.isEmpty()) {
            throw new IllegalArgumentException("description must not be empty.");
        }

        StringBuilder content = new StringBuilder();
        content.append("name=").append(repositoryName)
                .append("&visibility=").append("public");

        if (organization != null) {
            content.append("&namespace_id=").append(checkOrganizationExistsAndReturnId(organization.getName()));
        }

        content.append("&description=").append(description);
        Request request = request()
                .post(RequestBody.create(APPLICATION_FORM_URLENCODED, content.toString()))
                .url(GITLAB_URL + "/api/v4/projects")
                .build();
        final GitRepository repository = httpClient.executeAndParseJson(request, GitLabService::readGitRepository)
                .orElseThrow(() -> new NoSuchRepositoryException(repositoryName));
        return waitForRepository(repository.getFullName());
    }

    @Override
    public GitRepository createRepository(String repositoryName, String description) throws IllegalArgumentException {
        return createRepository(null, repositoryName, description);
    }

    @Override
    public Optional<GitRepository> getRepository(String name) {
        requireNonNull(name, "name must be specified.");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("repositoryName must not be empty.");
        }

        if (isValidGitRepositoryFullName(name)) {
            return getRepositoryByFullName(name);
        } else {
            checkGitRepositoryNameArgument(name);
            return getRepositoryByFullName(createGitRepositoryFullName(getLoggedUser().getLogin(), name));
        }
    }

    @Override
    public Optional<GitRepository> getRepository(GitOrganization organization, String repositoryName) {
        requireNonNull(organization, "organization must not be null.");
        checkGitRepositoryNameArgument(repositoryName);

        checkOrganizationExistsAndReturnId(organization.getName());

        return getRepositoryByFullName(createGitRepositoryFullName(organization.getName(), repositoryName));
    }

    private Optional<GitRepository> getRepositoryByFullName(String repositoryFullName) {
        checkGitRepositoryFullNameArgument(repositoryFullName);

        Request request = request()
                .get()
                .url(GITLAB_URL + "/api/v4/projects/" + urlEncode(repositoryFullName))
                .build();
        return httpClient.executeAndParseJson(request, GitLabService::readGitRepository);
    }

    @Override
    public void deleteRepository(String repositoryFullName) throws IllegalArgumentException {
        checkGitRepositoryFullNameArgument(repositoryFullName);

        Request request = request()
                .delete()
                .url(GITLAB_URL + "/api/v4/projects/" + urlEncode(repositoryFullName))
                .build();
        httpClient.execute(request);
    }

    @Override
    public GitHook createHook(GitRepository repository, String secret, URL webhookUrl, String... events) throws IllegalArgumentException {
        requireNonNull(repository, "repository must not be null.");
        requireNonNull(webhookUrl, "webhookUrl must not be null.");
        checkGitRepositoryFullNameArgument(repository.getFullName());

        final String[] effectiveEvents = events != null && events.length > 0 ? events : getSuggestedNewHookEvents();
        StringBuilder content = new StringBuilder();
        content.append("url=").append(webhookUrl);
        if (secret != null && secret.length() > 0) {
            content.append("&token=")
                    .append(urlEncode(secret));
        }
        for (String event : effectiveEvents) {
            content.append("&")
                    .append(event.toLowerCase())
                    .append("_events=true");
        }
        Request request = request()
                .post(RequestBody.create(APPLICATION_FORM_URLENCODED, content.toString()))
                .url(GITLAB_URL + "/api/v4/projects/" + urlEncode(repository.getFullName()) + "/hooks")
                .build();

        return httpClient.executeAndParseJson(request, this::readHook).orElse(null);
    }

    @Override
    public List<GitHook> getHooks(GitRepository repository) throws IllegalArgumentException {
        requireNonNull(repository, "repository must not be null.");
        checkGitRepositoryFullNameArgument(repository.getFullName());

        Request request = request()
                .get()
                .url(GITLAB_URL + "/api/v4/projects/" + urlEncode(repository.getFullName()) + "/hooks")
                .build();
        return httpClient.executeAndParseJson(request, (JsonNode tree) ->
                StreamSupport.stream(tree.spliterator(), false)
                        .map(this::readHook)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    @Override
    public Optional<GitHook> getHook(GitRepository repository, URL url) throws IllegalArgumentException {
        requireNonNull(repository, "repository must not be null.");
        requireNonNull(url, "url must not be null.");
        checkGitRepositoryFullNameArgument(repository.getFullName());

        return getHooks(repository).stream()
                .filter(h -> h.getUrl().equalsIgnoreCase(url.toString()))
                .findFirst();
    }

    @Override
    public void deleteWebhook(GitRepository repository, GitHook webhook) throws IllegalArgumentException {
        requireNonNull(repository, "repository must not be null.");
        requireNonNull(webhook, "webhook must not be null.");
        checkGitRepositoryFullNameArgument(repository.getFullName());

        Request request = request()
                .delete()
                .url(GITLAB_URL + "/api/v4/projects/" + urlEncode(repository.getFullName()) + "/hooks/" + webhook.getName())
                .build();
        httpClient.execute(request);
    }

    @Override
    public GitUser getLoggedUser() {
        Request request = request()
                .get()
                .url(GITLAB_URL + "/api/v4/user")
                .build();
        return httpClient.executeAndParseJson(request, tree ->
                ImmutableGitUser.of(tree.get("username").asText(),
                                    tree.get("avatar_url").asText()))
                .orElseThrow(IllegalStateException::new);
    }

    private String checkOrganizationExistsAndReturnId(final String name) {
        requireNonNull(name, "name must be specified.");

        final String url = GITLAB_URL + "/api/v4/groups/" + urlEncode(name);
        final Request request = request()
                .get()
                .url(url)
                .build();
        return httpClient.executeAndParseJson(request, n -> n.get("id").asText())
                .orElseThrow(() -> new NoSuchOrganizationException("User does not belong to organization '" + name + "' or the organization does not exist"));
    }

    private Request.Builder request() {
        return securedRequest(getIdentity(), PRIVATE_TOKEN);
    }

    private GitHook readHook(JsonNode tree) {
        ImmutableGitHook.Builder builder = ImmutableGitHook.builder()
                .name(tree.get("id").asText())
                .url(tree.get("url").asText());
        Iterator<Map.Entry<String, JsonNode>> fields = tree.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            if (fieldName.endsWith("_events") && entry.getValue().asBoolean()) {
                builder.addEvents(fieldName.substring(0, fieldName.lastIndexOf("_events")));
            }
        }
        return builder.build();
    }

    private static GitRepository readGitRepository(JsonNode node) {
        return ImmutableGitRepository.builder()
                .fullName(node.get("path_with_namespace").asText())
                .homepage(URI.create(node.get("web_url").asText()))
                .gitCloneUri(URI.create(node.get("http_url_to_repo").asText()))
                .build();
    }

    @Override
    public String[] getSuggestedNewHookEvents() {
        return new String[]{
                PUSH.id(),
                MERGE_REQUESTS.id(),
                ISSUES.id()
        };
    }
}