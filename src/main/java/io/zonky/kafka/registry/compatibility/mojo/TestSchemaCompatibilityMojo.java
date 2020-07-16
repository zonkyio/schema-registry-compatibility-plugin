package io.zonky.kafka.registry.compatibility.mojo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.avro.Schema;
import org.apache.avro.SchemaParseException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.zonky.kafka.registry.compatibility.domain.CompatibilityCheckResult;
import io.zonky.kafka.registry.compatibility.domain.SchemaFileCheckingContext;
import io.zonky.kafka.registry.compatibility.exception.SchemaCompatibilityCheckException;
import io.zonky.kafka.registry.compatibility.util.LazySupplier;

@Mojo(name = "test-compatibility")
public class TestSchemaCompatibilityMojo extends AbstractMojo {

    /**
     * Filesets definitons that should match local schema files (*.avsc) whose schema definitions should be
     * compatibility-checked against remote schema registry.
     */
    @SuppressWarnings("MismatchedReadAndWriteOfArray")
    @Parameter(required = true)
    private FileSet[] schemaFileSets = new FileSet[]{};

    /**
     * A list of files that should be parsed first thus making them importable by other schemas (including each other)
     */
    @Parameter
    private List<String> imports = new LinkedList<>();

    /**
     * Regex pattern (in java regex syntax), that is used to extract following sub-components of remote schema registry's subject names.
     * - topicname
     * - schematypefullname
     * <p>
     * For example if you're using subject naming pattern such as "name.of.topic-name.of.schema.Type-value", then
     * following regex may be used to extract topicname and schematypefullname:
     * `    *
     * ^(?<topicname>.+)-(?<schematypefullname>.[^-]+)-value
     */
    @Parameter(required = true)
    private String schemaRegistrySubjectNamePattern;

    /**
     * Reference to the maven project being currently built.
     */
    @Parameter(required = true, defaultValue = "${project}")
    private MavenProject project;

    /**
     * URLs to remote schema registry/registries.
     * <p/>
     * Typically configured in pom.xml (<code>plugin -> configuration -> schemaRegistryUrls</code>).
     * <p/>
     * Can also be overridden via runtime environment property <code>-Dschema-registry-compatibility-plugin.schema-registry-urls=http://some.url</code>
     *
     */
    @Parameter(property = "schema-registry-compatibility-plugin.schema-registry-urls", required = true)
    private List<String> schemaRegistryUrls = new LinkedList<>();

    /**
     * Basic http auth user/password configuration. Uses "user:password" format.
     */
    @Parameter
    private String userInfoConfig;

    /**
     * Finds all the files that should get checked, loads their local schemas and check them against remote schema registry.
     */
    @Override
    public void execute() throws MojoExecutionException {
        final FileSetManager fileSetManager = new FileSetManager();
        final Supplier<SchemaRegistryClient> clientSupplier = new LazySupplier<>(this::buildClient);

        // Find schema files to be check, load their local schemas and check them against all matching subjects in remote schema registry
        final List<SchemaFileCheckingContext> schemaFileCheckingContexts = Arrays.stream(schemaFileSets)
                .map(this.getIncludedFiles(fileSetManager))
                .flatMap(List::stream)
                .map(this::toSchemaFileCheckingContext)
                .map(this::addSchema)
                .map(this.addMatchingRegistrySubjectNames(new LazySupplier<>(() -> fetchSchemaRegistrySubjects(clientSupplier))))
                .map(this.addCompatibilityCheckResults(clientSupplier))
                .collect(Collectors.toList());

        if (schemaFileCheckingContexts.isEmpty()) {
            getLog().warn("No schema files found to be checked for compatibility.");
            return;
        }

        // Print result of compatibility checks results
        final List<CompatibilityCheckResult> incompatibilities = schemaFileCheckingContexts.stream()
                .map(s -> s.getCompatibilityCheckResults(false))
                .flatMap(List::stream)
                .collect(Collectors.toList());

        if (!incompatibilities.isEmpty()) {
            throw new MojoExecutionException(
                    String.format("%s local schema(s) found to be incompatible with current version in remote schema registry: \n" +
                                    incompatibilities.stream()
                                            .map(cr -> String.format("schema type '%s' is not compatible with schema registry subject '%s'", cr.getSchema().getFullName(), cr.getRegistrySubjectName()))
                                            .collect(Collectors.joining("\n"))
                            , incompatibilities.size()
                    )
            );
        }

        // print checking statistics for each file
        getLog().info(" Schema checks complete. Following files were checked:");
        schemaFileCheckingContexts.forEach(ctx -> {
            final List<CompatibilityCheckResult> incompatibleResults = ctx.getCompatibilityCheckResults().stream().filter(res -> !res.isCompatible()).collect(Collectors.toList());
            final long compatibleCount = ctx.getCompatibilityCheckResults().size() - incompatibleResults.size();
            getLog().info(String.format(" - '%s' (compatible_subjects=%s, incompatible_subjects=%s)", ctx.getFile().getName(), compatibleCount, incompatibleResults.size()));
        });

        // in case some of the files with local schema were not checked at all, WARN it
        final List<SchemaFileCheckingContext> filesWithNoChecks = schemaFileCheckingContexts.stream().filter(ctx -> ctx.getCompatibilityCheckResults().isEmpty()).collect(Collectors.toList());
        if (!filesWithNoChecks.isEmpty()) {
            getLog().warn(
                    String.format("%s local schema(s) were NOT CHECKED against any subject in remote schema registry: \n" +
                                    filesWithNoChecks.stream()
                                            .map(cr -> " - " + cr.getFile().getName())
                                            .collect(Collectors.joining("\n")),
                            filesWithNoChecks.size()
                    )
            );
        }
    }

    /**
     * Constructs a SchemaFileCheckingContext object from specified schemaFile.
     *
     * @param schemaFile single .avsc avro schema file
     */
    private SchemaFileCheckingContext toSchemaFileCheckingContext(final File schemaFile) {
        final String schemaTypeFullName = getSchemaTypeFullName(schemaFile);
        return new SchemaFileCheckingContext(schemaFile, schemaTypeFullName);
    }

    /**
     * Extracts a list of file from single FileSet definition
     *
     * @param fileSetManager maven's fileset manager component
     */
    private Function<FileSet, List<File>> getIncludedFiles(final FileSetManager fileSetManager) {
        return fs -> Arrays.stream(fileSetManager.getIncludedFiles(fs)).map(fn -> new File(fs.getDirectory(), fn)).collect(Collectors.toList());
    }

    /**
     * Parses schema of the checked file and adds it to the context.
     *
     * @param schemaFileCheckingContext context representing a single checked local schema file
     */
    private SchemaFileCheckingContext addSchema(SchemaFileCheckingContext schemaFileCheckingContext) {
        Schema.Parser parser = newParser();
        getLog().debug(
                String.format(
                        "Loading schema for subject(%s) from %s.",
                        schemaFileCheckingContext.getSchemaTypeFullName(),
                        schemaFileCheckingContext.getFile()
                )
        );

        try (FileInputStream inputStream = new FileInputStream(schemaFileCheckingContext.getFile())) {
            final Optional<Schema> alreadyDefinedSchema = parser.getTypes().entrySet().stream()
                    .filter(type -> schemaFileCheckingContext.getSchemaTypeFullName().equals(type.getValue().getFullName()))
                    .findFirst()
                    .map(Map.Entry::getValue);
            final Schema schema = alreadyDefinedSchema.isPresent()
                    ? alreadyDefinedSchema.get()
                    : parser.parse(inputStream);
            schemaFileCheckingContext.setSchema(schema);
            return schemaFileCheckingContext;
        } catch (IOException | SchemaParseException e) {
            getLog().error("Exception thrown while loading " + schemaFileCheckingContext.getFile(), e);
            throw new SchemaCompatibilityCheckException(e);
        }
    }

    /**
     * Adds subject names from remote schema registry which match currently checked file and ads them to context.
     *
     * @param schemaRegistrySubjectsSupplier function that provides list of remote schema registry subjects
     */
    private Function<SchemaFileCheckingContext, SchemaFileCheckingContext> addMatchingRegistrySubjectNames(final Supplier<Collection<String>> schemaRegistrySubjectsSupplier) {
        return (context) -> {
            final Pattern subjectPartsExtractionPattern = Pattern.compile(schemaRegistrySubjectNamePattern);

            final Map<String, List<String>> subjectNamesByFullSchemaTypeNames = schemaRegistrySubjectsSupplier.get().stream()
                    .collect(Collectors.groupingBy(subj -> extractFullTypeNameFromSubject(subj, subjectPartsExtractionPattern)));

            context.addMatchingRegistrySubjectNames(subjectNamesByFullSchemaTypeNames.getOrDefault(context.getSchemaTypeFullName(), Collections.emptyList()));

            return context;
        };
    }

    /**
     * Performs compatibility check for current file and all its matching remote schema registry subjects. Adds check results to the context.
     *
     * @param clientSupplier supplier that provides configured SchemaRegistryClient
     */
    private Function<SchemaFileCheckingContext, SchemaFileCheckingContext> addCompatibilityCheckResults(final Supplier<SchemaRegistryClient> clientSupplier) {
        return (schemaFileCheckingContext -> {
            final String schemaTypeFullName = schemaFileCheckingContext.getSchemaTypeFullName();
            final Schema schema = schemaFileCheckingContext.getSchema();
            final String schemaFilePath = schemaFileCheckingContext.getFile().getPath();

            for (final String registrySubjectName : schemaFileCheckingContext.getMatchingRegistrySubjectNames()) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug(String.format("Calling register('%s', '%s')", schemaTypeFullName, schema.toString(true)));
                }
                try {
                    final boolean compatible = clientSupplier.get().testCompatibility(registrySubjectName, schema);
                    schemaFileCheckingContext.addCompatiblityCheckResult(new CompatibilityCheckResult(registrySubjectName, schema, compatible));
                    if (!compatible) {
                        if (getLog().isDebugEnabled()) {
                            getLog().error(String.format("Incompatibility found between schema file %s and registry subject %s", schemaTypeFullName, schemaFilePath));
                        }
                    }
                } catch (IOException | RestClientException e) {
                    if (getLog().isDebugEnabled()) {
                        getLog().error(String.format("Exception found between schema file %s and registry subject %s", schemaTypeFullName, schemaFilePath));
                    }
                    throw new SchemaCompatibilityCheckException("Compatibility check failed", e);
                }
            }
            return schemaFileCheckingContext;
        });
    }

    /**
     * Takes schema registry subject's name and extracts full name of schema type from it.
     *
     * @param subjectName                   remote schema registry subject name
     * @param subjectPartsExtractionPattern regex that should contain "schematypefullname" named group
     */
    private String extractFullTypeNameFromSubject(final String subjectName, final Pattern subjectPartsExtractionPattern) {
        final Matcher matcher = subjectPartsExtractionPattern.matcher(subjectName);
        if (matcher.find()) {
            return matcher.group("schematypefullname");
        } else {
            getLog().warn(String.format("Unable to extract full type name from subject [%s], skipping verification of this subject.", subjectName));
            return "UNRESOLVED_TOPIC_NAME";
        }
    }

    /**
     * Reads provided avro schema file (json), parses it and builds full name (namespace+name) of the type defined in file.
     *
     * @param filePath file with avro (json) schema
     */
    private String getSchemaTypeFullName(final File filePath) {
        try {
            final JsonObject schemaDescJSON = JsonParser.parseReader(new FileReader(filePath)).getAsJsonObject();
            final String namespace = schemaDescJSON.getAsJsonPrimitive("namespace").getAsString();
            final String name = schemaDescJSON.getAsJsonPrimitive("name").getAsString();
            return namespace + "." + name;
        } catch (FileNotFoundException e) {
            throw new SchemaCompatibilityCheckException(e);
        }
    }

    /**
     * Performs remote API call against schema registry. Fetches all subject names, which are currently defined in schema registry.
     *
     * @param clientSupplier function that provides schema registry client
     */
    private Collection<String> fetchSchemaRegistrySubjects(final Supplier<SchemaRegistryClient> clientSupplier) {
        try {
            return clientSupplier.get().getAllSubjects();
        } catch (IOException | RestClientException e) {
            throw new SchemaCompatibilityCheckException(e);
        }
    }

    /**
     * Builds a new schema parser. Respects dependencies, if they're provided in plugin configuration.
     */
    private Schema.Parser newParser() {
        if (imports.isEmpty()) {
            return new Schema.Parser();
        }
        return parserWithDependencies(imports);
    }

    /**
     * Creates a parser, which already has pre-parsed dependencies ("imports")
     *
     * @param dependencies list of schema files which should be treated as dependencies ("imports")
     */
    private Schema.Parser parserWithDependencies(List<String> dependencies) {
        final Schema.Parser parserWithDependencies = new Schema.Parser();

        for (String dependency : dependencies) {
            try (FileInputStream inputStream = new FileInputStream(dependency)) {
                parserWithDependencies.parse(inputStream);
                getLog().debug(String.format("Parsing imports:%s", dependency));
            } catch (IOException | SchemaParseException e) {
                throw new SchemaCompatibilityCheckException(
                        String.format("Unable to parse dependency %s", dependency), e);
            }
        }
        return parserWithDependencies;
    }

    /**
     * Creates configured instance of schema registry client.
     */
    protected SchemaRegistryClient buildClient() {
        Map<String, String> config = new HashMap<>();
        if (userInfoConfig != null) {
            // Note that BASIC_AUTH_CREDENTIALS_SOURCE is not configurable as the plugin only supports
            // a single schema registry URL, so there is no additional utility of the URL source.
            config.put(SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO");
            config.put(SchemaRegistryClientConfig.USER_INFO_CONFIG, userInfoConfig);
        }
        return new CachedSchemaRegistryClient(this.schemaRegistryUrls, 1000, config);
    }
}