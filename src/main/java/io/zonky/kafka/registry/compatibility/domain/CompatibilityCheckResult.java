package io.zonky.kafka.registry.compatibility.domain;

import org.apache.avro.Schema;

/**
 * Domain object holding the result of compatibility check of a single local schema against a single remote registry subject.
 */
public class CompatibilityCheckResult {

    private final String registrySubjectName;
    private final Schema schema;
    private final boolean compatible;

    public CompatibilityCheckResult(final String registrySubjectName, final Schema schema, final boolean compatible) {
        this.registrySubjectName = registrySubjectName;
        this.schema = schema;
        this.compatible = compatible;
    }

    public String getRegistrySubjectName() {
        return registrySubjectName;
    }

    public Schema getSchema() {
        return schema;
    }

    public boolean isCompatible() {
        return compatible;
    }
}
