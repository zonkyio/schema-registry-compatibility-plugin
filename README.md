# kafka-schema-registry-compatibility-plugin

## Introduction

This maven plugin is fork / rewrite of [confluentinc's schema-registry/maven-plugin](https://github.com/confluentinc/schema-registry/tree/master/maven-plugin). 
It only provides schema compatibility checking against remote schema registry. Compared to confluent's plugin, this plugins is adding several features which 
would be non-trivial to add to confluent's plugin without heavy refactoring and breaking their plugin's API.

## Features

The plugin performs checks that the schema types (defined in *.avsc files in your local maven module) are compatible with same types already defined in remote 
schema registry. 

- You can use FileSets (ie. file path wildcards etc) when defining sources of local avro files via `schemaFileSets`. This  
- All the remote subject names are fetched from remote schema registry. Once known, they're mapped (using `schemaRegistrySubjectNamePattern`) to your local 
schema files. 
- Every local schema is checked against all its matching remote schema registry subjects

## Process of compatibility checking

Every local schema file (as configured via `schemaFileSets`) gets checked against all matching remote schema-repository subjects. In order to find
matching subjects, full schema schema type name (extracted from local schema file) is used.
 
First the names of all the existing subjects are fetched from schema registry and then  `schemaRegistrySubjectNamePattern` is applied to each subject in order to 
extract regex group called `schematypefullname` (regex group with this name must be defined in `schemaRegistrySubjectNamePattern`). Once `schematypefullname` has 
been extracted, the subject can be paired to checked local schema file.

## Typical configuration

```xml
<plugins>
    <plugin>
        <groupId>io.zonky</groupId>
        <artifactId>kafka-schema-registry-compatibility-plugin</artifactId>
        <version>1.0</version>
        <executions>
            <execution>
                <id>test-schema-compatibility</id>
                <phase>process-resources</phase>
                <goals>
                    <goal>test-compatibility</goal>
                </goals>
                <configuration>
                    <imports>
                        <import>${project.basedir}/src/main/resources/avro/instalment.avsc</import>
                        <import>${project.basedir}/src/main/resources/avro/instalmentCalendarChanged.avsc</import>
                    </imports>
                    <schemaRegistryUrls>
                        <param>http://localhost:8081</param>
                    </schemaRegistryUrls>
                    <schemaRegistrySubjectNamePattern>
                        <![CDATA[^(?<topicname>.+)-(?<schematypefullname>.[^-]+)-value]]>
                    </schemaRegistrySubjectNamePattern>
                    <schemaFileSets>
                        <fileset>
                            <directory>src/main/resources/avro</directory>
                            <includes>
                                <include>**/*.avsc</include>
                            </includes>
                        </fileset>
                    </schemaFileSets>
                </configuration>
            </execution>
        </executions>
    </plugin>
</plugins> 
```
