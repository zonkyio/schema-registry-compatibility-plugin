package io.zonky.kafka.registry.compatibility.domain;

import org.apache.avro.Schema;

import java.io.File;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Context object holding data for a single schema file used in compatibility checking.
 */
public class SchemaFileCheckingContext {
    private final File file;
    private final String schemaTypeFullName;
    private final List<String> matchingRegistrySubjectNames = new LinkedList<>();
    private final List<CompatibilityCheckResult> compatibilityCheckResults = new LinkedList<>();
    private Schema schema;

    public SchemaFileCheckingContext(final File file, final String schemaTypeFullName) {
        this.file = file;
        this.schemaTypeFullName = schemaTypeFullName;
    }

    public String getSchemaTypeFullName() {
        return schemaTypeFullName;
    }

    public File getFile() {
        return file;
    }

    public List<String> getMatchingRegistrySubjectNames() {
        return matchingRegistrySubjectNames;
    }

    public void addMatchingRegistrySubjectNames(final Collection<String> subjectNames) {
        matchingRegistrySubjectNames.addAll(subjectNames);
    }

    public void addCompatiblityCheckResult(CompatibilityCheckResult compatibilityCheckResult) {
        this.compatibilityCheckResults.add(compatibilityCheckResult);
    }

    public List<CompatibilityCheckResult> getCompatibilityCheckResults() {
        return compatibilityCheckResults;
    }

    public List<CompatibilityCheckResult> getCompatibilityCheckResults(final boolean compatible) {
        return compatibilityCheckResults.stream().filter(res -> res.isCompatible() == compatible).collect(Collectors.toList());
    }

    public void setSchema(final Schema schema) {
        this.schema = schema;
    }

    public Schema getSchema() {
        return schema;
    }
}
