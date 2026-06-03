/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.io.File;
import java.io.IOException;

public class ServicePackLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public ServiceDefinition load(File file) throws IOException {
        return YAML.readValue(file, ServiceDefinition.class);
    }
}
